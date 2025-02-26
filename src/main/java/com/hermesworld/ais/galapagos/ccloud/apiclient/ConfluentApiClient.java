package com.hermesworld.ais.galapagos.ccloud.apiclient;

import com.auth0.jwt.JWT;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hermesworld.ais.galapagos.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;

import javax.annotation.CheckReturnValue;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

@Component
@Slf4j
public class ConfluentApiClient {

    private final static String BASE_URL = "https://confluent.cloud/api";
    private final static String LOGIN_ENDPOINT = "/sessions";
    private final static String ME_ENDPOINT = "/me";
    private final static String LIST_API_KEYS_ENDPOINT = "/api_keys?account_id=%s&cluster_id=%s";
    private final static String CREATE_API_KEY_ENDPOINT = "/api_keys";
    private final static String DELETE_API_KEY_ENDPOINT = "/api_keys/%d";

    private final static String SERVICE_ACCOUNTS_ENDPOINT = "/service_accounts";

    private final WebClient client;

    private final ObjectMapper mapper = JsonUtil.newObjectMapper();

    private String sessionToken;

    private Integer userId;

    public ConfluentApiClient() {
        this(WebClient.builder().baseUrl(BASE_URL)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build());
    }

    public ConfluentApiClient(WebClient client) {
        this.client = client;
    }

    public boolean isLoggedIn() {
        if (sessionToken == null || userId == null) {
            return false;
        }
        // check if session token has expired
        Date exp = JWT.decode(sessionToken).getExpiresAt();
        return exp != null && new Date().before(exp);
    }

    @CheckReturnValue
    public Mono<Boolean> login(String userName, String password) {
        JSONObject authData = new JSONObject();
        authData.put("email", userName);
        authData.put("password", password);

        return doMethod(WebClient::post, LOGIN_ENDPOINT, authData.toString(), response -> {
            this.sessionToken = new JSONObject(response).getString("token");
            return true;
        }, "Could not log in to Confluent Cloud", false).flatMap(b -> getUserId());
    }

    public Mono<List<ApiKeyInfo>> listApiKeys(String envId, String clusterId) {
        return doGet(String.format(LIST_API_KEYS_ENDPOINT, envId, clusterId),
                response -> response.getJSONArray("api_keys"),
                mapper.getTypeFactory().constructCollectionType(List.class, ApiKeyInfo.class),
                "Could not list API Keys");
    }

    public Mono<List<ServiceAccountInfo>> listServiceAccounts() {
        return doGet(SERVICE_ACCOUNTS_ENDPOINT, response -> response.getJSONArray("users"),
                mapper.getTypeFactory().constructCollectionType(List.class, ServiceAccountInfo.class),
                "Could not list Service Accounts");
    }

    public Mono<ServiceAccountInfo> createServiceAccount(String accountName, String accountDescription) {
        JSONObject req = new JSONObject();
        req.put("service_name", accountName);
        req.put("service_description", accountDescription);
        String reqJson = new JSONObject(Map.of("user", req)).toString();

        return doPost(SERVICE_ACCOUNTS_ENDPOINT, reqJson, response -> {
            try {
                String user = new JSONObject(response).getJSONObject("user").toString();
                return mapper.readValue(user, ServiceAccountInfo.class);
            }
            catch (JSONException | JsonProcessingException e) {
                throw new ConfluentApiException("Could not read generated Service Account from response", e);
            }
        }, "Could not create Service Account");
    }

    public Mono<ApiKeyInfo> createApiKey(String envId, String clusterId, String description, Integer userId) {
        JSONObject keyReq = new JSONObject();
        keyReq.put("account_id", envId);
        keyReq.put("logical_clusters", new JSONArray(List.of(new JSONObject(Map.of("id", clusterId)))));
        keyReq.put("user_id", userId);
        keyReq.put("description", description);
        String keyReqJson = new JSONObject(Map.of("api_key", keyReq)).toString();

        return doPost(CREATE_API_KEY_ENDPOINT, keyReqJson, response -> {
            try {
                String key = new JSONObject(response).getJSONObject("api_key").toString();
                return mapper.readValue(key, ApiKeyInfo.class);
            }
            catch (JSONException | JsonProcessingException e) {
                throw new ConfluentApiException("Could not read generated API key from response", e);
            }
        }, "Could not create API Key");
    }

    public Mono<Boolean> deleteApiKey(ApiKeyInfo apiKeyInfo) {
        JSONObject keyReq = new JSONObject();
        keyReq.put("account_id", apiKeyInfo.getAccountId());
        keyReq.put("user_id", apiKeyInfo.getUserId());
        keyReq.put("id", apiKeyInfo.getId());
        String keyReqJson = new JSONObject(Map.of("api_key", keyReq)).toString();

        return doMethod(client -> client.method(HttpMethod.DELETE),
                String.format(DELETE_API_KEY_ENDPOINT, apiKeyInfo.getId()), keyReqJson, response -> true,
                "Could not delete API Key", true);
    }

    private Mono<Boolean> getUserId() {
        return auth(client.get().uri(ME_ENDPOINT)).retrieve().bodyToMono(String.class)
                .handle(jsonErrorHandler(msg -> new ConfluentApiException("Could not get user info: " + msg)))
                .map(response -> {
                    try {
                        this.userId = new JSONObject(response).getJSONObject("user").getInt("id");
                        return true;
                    }
                    catch (JSONException e) {
                        throw new ConfluentApiException("Invalid format of received user info", e);
                    }
                });
    }

    private <S extends WebClient.RequestHeadersSpec<S>> WebClient.RequestHeadersSpec<S> auth(
            WebClient.RequestHeadersSpec<S> spec) {
        log.debug("Authorizing Confluent API Request with session token {}", this.sessionToken);
        return spec.header("Authorization", "Bearer " + this.sessionToken);
    }

    private Mono<Boolean> assertLoggedIn() {
        if (this.sessionToken == null) {
            return Mono.error(() -> new LoginException("Must log in into Confluent Cloud first"));
        }
        return Mono.just(true);
    }

    private <T> Mono<T> doPost(String uri, String body, Function<String, T> responseBodyHandler, String errorMessage) {
        log.debug("Confluent API POST Request: uri = {}, body = {}", uri, body);
        return doMethod(WebClient::post, uri, body, responseBodyHandler, errorMessage, true);
    }

    private <T> Mono<T> doGet(String uri, Function<JSONObject, Object> jsonExtractor, JavaType resultType,
            String errorMessage) {
        log.debug("Confluent API GET Request: uri = {}", uri);
        return assertLoggedIn().flatMap(b -> auth(client.get().uri(uri)).retrieve()
                .onStatus(status -> status.isError(), errorResponseHandler(uri, errorMessage)).bodyToMono(String.class))
                .map(body -> {
                    try {
                        String json = jsonExtractor.apply(new JSONObject(body)).toString();
                        return mapper.readValue(json, resultType);
                    }
                    catch (JSONException | JsonProcessingException e) {
                        throw new ConfluentApiException("Could not read API keys from response", e);
                    }
                });
    }

    private <T> Mono<T> doMethod(Function<WebClient, WebClient.RequestBodyUriSpec> method, String uri, String body,
            Function<String, T> responseBodyHandler, String errorMessage, boolean checkLogin) {
        return (checkLogin ? assertLoggedIn() : Mono.just(true))
                .flatMap(b -> auth(method.apply(client).uri(uri).body(BodyInserters.fromValue(body))).retrieve()
                        .onStatus(status -> status.isError(), errorResponseHandler(uri, errorMessage))
                        .bodyToMono(String.class))
                .map(responseBodyHandler);
    }

    private BiConsumer<String, SynchronousSink<String>> jsonErrorHandler(
            Function<String, ? extends Exception> exceptionSupplier) {
        return (response, sink) -> {
            JSONObject result = new JSONObject(response);
            if (result.has("error") && !result.isNull("error")) {
                sink.error(exceptionSupplier.apply(result.getString("error")));
            }
            else {
                sink.next(response);
            }
        };
    }

    private Function<ClientResponse, Mono<? extends Throwable>> errorResponseHandler(String uri, String errorMessage) {
        return response -> response.bodyToMono(String.class).map(body -> {
            try {
                JSONObject result = new JSONObject(body);
                if (result.has("error") && !result.isNull("error")) {
                    return new ConfluentApiException(errorMessage + ": " + result.getString("error"));
                }
            }
            catch (JSONException e) {
                // then fallback to simple exception
            }
            return new ConfluentApiException(
                    errorMessage + ": Server returned " + response.rawStatusCode() + " for " + uri);

        }).defaultIfEmpty(new ConfluentApiException(
                errorMessage + ": Server returned " + response.rawStatusCode() + " for " + uri));
    }

}
