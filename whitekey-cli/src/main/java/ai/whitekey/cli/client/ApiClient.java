package ai.whitekey.cli.client;

import ai.whitekey.api.dto.Action;
import ai.whitekey.api.dto.CreateCredentialRequest;
import ai.whitekey.api.dto.CreateRuleRequest;
import ai.whitekey.api.dto.Credential;
import ai.whitekey.api.dto.CredentialPreset;
import ai.whitekey.api.dto.ResolveActionRequest;
import ai.whitekey.api.dto.Rule;
import ai.whitekey.api.dto.Status;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

public class ApiClient {

    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String APPLICATION_JSON = "application/json";
    private static final String BEARER_PREFIX = "Bearer ";

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    public static final int MAX_ACTIONS_LIMIT = 100;

    public static final String PATH_STATUS = "/status";
    public static final String PATH_RULES = "/rules";
    public static final String PATH_CREDENTIALS = "/credentials";
    public static final String PATH_CREDENTIAL_PRESETS = "/credentials/presets";
    public static final String PATH_ACTIONS = "/actions";

    private final String serverUrl;
    private final String adminToken;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ApiClient(String serverUrl, String adminToken) {
        this.serverUrl = resolveServerUrl(serverUrl);
        this.adminToken = resolveAdminToken(adminToken);
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule()).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public Status getStatus() {
        return get(PATH_STATUS, Status.class);
    }

    public List<Rule> getRules() {
        Rule[] rules = get(PATH_RULES, Rule[].class);
        return rules != null ? List.of(rules) : List.of();
    }

    public Rule createRule(CreateRuleRequest request) {
        return post(PATH_RULES, request, Rule.class);
    }

    public void deleteRule(String name) {
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        delete(PATH_RULES + "/" + encoded);
    }

    public List<Credential> getCredentials() {
        Credential[] creds = get(PATH_CREDENTIALS, Credential[].class);
        return creds != null ? List.of(creds) : List.of();
    }

    public List<CredentialPreset> getCredentialPresets() {
        CredentialPreset[] presets = get(PATH_CREDENTIAL_PRESETS, CredentialPreset[].class);
        return presets != null ? List.of(presets) : List.of();
    }

    public Credential createCredential(CreateCredentialRequest request) {
        return post(PATH_CREDENTIALS, request, Credential.class);
    }

    public void deleteCredential(String name) {
        delete(PATH_CREDENTIALS + "/" + name);
    }

    public List<Action> getPendingApprovals() {
        return getHistory(MAX_ACTIONS_LIMIT, "AWAITING_APPROVAL", null, null).stream()
                .sorted(Comparator.comparing(Action::id, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    public void resolveApproval(String actionId, String decision, boolean saveRule) {
        postNoContent(PATH_ACTIONS + "/" + actionId + "/resolve", new ResolveActionRequest(decision, saveRule));
    }

    public List<Action> getHistory(int limit, String status, String method, String host) {
        int validLimit = Math.max(1, Math.min(limit, MAX_ACTIONS_LIMIT));
        StringBuilder path = new StringBuilder(PATH_ACTIONS + "?page=0&limit=").append(validLimit);
        if (status != null && !status.isBlank()) {
            path.append("&status=").append(URLEncoder.encode(status.trim().toUpperCase(), StandardCharsets.UTF_8));
        }
        if (method != null && !method.isBlank()) {
            path.append("&method=").append(URLEncoder.encode(method.trim().toUpperCase(), StandardCharsets.UTF_8));
        }
        if (host != null && !host.isBlank()) {
            path.append("&host=").append(URLEncoder.encode(host.trim(), StandardCharsets.UTF_8));
        }

        Action[] actions = get(path.toString(), Action[].class);
        return actions != null ? List.of(actions) : List.of();
    }

    public void clearHistory() {
        delete(PATH_ACTIONS);
    }

    private <T> T get(String path, Class<T> clazz) {
        HttpResponse<String> response = send(newRequestBuilder(path).GET().build());
        return deserialize(response.body(), clazz);
    }

    private <T> T post(String path, Object body, Class<T> clazz) {
        String json = serialize(body);
        HttpRequest request = newRequestBuilder(path)
                .header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = send(request);
        return deserialize(response.body(), clazz);
    }

    private void postNoContent(String path, Object body) {
        HttpRequest.Builder builder = newRequestBuilder(path);
        if (body != null) {
            builder.header(HEADER_CONTENT_TYPE, APPLICATION_JSON);
            builder.POST(HttpRequest.BodyPublishers.ofString(serialize(body)));
        } else {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        }
        send(builder.build());
    }

    private void delete(String path) {
        send(newRequestBuilder(path).DELETE().build());
    }

    private HttpRequest.Builder newRequestBuilder(String path) {
        String fullUrl = serverUrl + path;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .timeout(REQUEST_TIMEOUT);
        if (adminToken != null && !adminToken.isBlank()) {
            builder.header(HEADER_AUTHORIZATION, BEARER_PREFIX + adminToken.strip());
        }
        return builder;
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= HttpURLConnection.HTTP_OK && status < HttpURLConnection.HTTP_MULT_CHOICE) {
                return response;
            }
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw new CliException("Authentication failed (401 Unauthorized). Verify your admin token.");
            }
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new CliException("Resource not found (404).");
            }
            String body = response.body();
            String errorMsg = body != null && !body.isBlank() ? body : "HTTP " + status;
            throw new CliException("Server error (" + status + "): " + errorMsg);
        } catch (ConnectException | HttpConnectTimeoutException e) {
            throw new CliException("Unable to connect to WhiteKey server at " + serverUrl);
        } catch (IOException e) {
            throw new CliException("Network error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException("Request interrupted.");
        }
    }

    private <T> T deserialize(String json, Class<T> clazz) {
        if (json == null || json.isBlank() || clazz == Void.class) {
            return null;
        }
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            throw new CliException("Failed to parse server response: " + e.getMessage());
        }
    }

    private String serialize(Object obj) {
        if (obj == null) return "{}";
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new CliException("Failed to serialize request: " + e.getMessage());
        }
    }

    private static String resolveServerUrl(String explicitUrl) {
        if (explicitUrl != null && !explicitUrl.isBlank()) {
            return explicitUrl.trim();
        }
        String envUrl = System.getenv(CliConfigDefaults.ENV_SERVER_URL);
        if (envUrl != null && !envUrl.isBlank()) {
            return envUrl.trim();
        }
        return CliConfigDefaults.DEFAULT_SERVER_URL;
    }

    private static String resolveAdminToken(String explicitToken) {
        if (explicitToken != null && !explicitToken.isBlank()) {
            return explicitToken.trim();
        }
        String envToken = System.getenv(CliConfigDefaults.ENV_ADMIN_TOKEN);
        if (envToken != null && !envToken.isBlank()) {
            return envToken.trim();
        }
        Path tokenFile = CliConfigDefaults.resolveDefaultTokenFile();
        if (tokenFile != null && Files.exists(tokenFile)) {
            try {
                return Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
            } catch (IOException ignored) {
            }
        }
        return null;
    }
}
