package ai.whitekey.proxy;

import ai.whitekey.api.dto.RuleEffect;
import ai.whitekey.config.ProxyProperties;
import ai.whitekey.entity.ActionEntity;
import ai.whitekey.entity.ActionStatus;
import ai.whitekey.policy.HttpPolicyValidator;
import ai.whitekey.service.ActionService;
import ai.whitekey.service.ApprovalWaitRegistry;
import ai.whitekey.service.CredentialInjectionService;
import ai.whitekey.service.PolicyEvaluationService;
import ai.whitekey.service.PolicyEvaluationService.EvaluationResult;
import ai.whitekey.service.PolicyEvaluationService.HeaderEvaluationResult;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import io.netty.handler.codec.http.HttpHeaderNames;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javax.net.ssl.SSLException;

@Service
public class AgentProxyService {

    public static final int HTTP_CLIENT_CLOSED_REQUEST = 499;

    public static final String ERROR_CLIENT_DISCONNECTED = "client_disconnected";
    public static final String ERROR_INTERRUPTED = "interrupted";
    public static final String ERROR_APPROVAL_ERROR = "approval_error";
    public static final String ERROR_APPROVAL_REJECTED = "approval_rejected";
    public static final String ERROR_APPROVAL_EXPIRED = "approval_expired";
    public static final String ERROR_APPROVAL_PENDING = "approval_pending";
    public static final String ERROR_POLICY_DENIED = "policy_denied";
    public static final String ERROR_INVALID_REQUEST = "invalid_request";
    public static final String ERROR_UPSTREAM_TLS_FAILED = "upstream_tls_failed";
    public static final String ERROR_UPSTREAM_CONNECT_FAILED = "upstream_connect_failed";
    public static final String ERROR_UPSTREAM_IO_FAILED = "upstream_io_failed";
    public static final String ERROR_BODY_READ = "body_read_error";
    public static final String WHITEKEY_HEADER_PREFIX = "x-whitekey-";

    private static final Set<String> HOP_BY_HOP_HEADERS =
            Set.of(
                    HttpHeaderNames.CONNECTION.toString(),
                    "keep-alive",
                    HttpHeaderNames.PROXY_AUTHENTICATE.toString(),
                    HttpHeaderNames.PROXY_AUTHORIZATION.toString(),
                    "proxy-connection",
                    HttpHeaderNames.TE.toString(),
                    HttpHeaderNames.TRAILER.toString(),
                    HttpHeaderNames.TRANSFER_ENCODING.toString(),
                    HttpHeaderNames.UPGRADE.toString(),
                    HttpHeaderNames.HOST.toString(),
                    HttpHeaderNames.CONTENT_LENGTH.toString());

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpPolicyValidator validator;
    private final PolicyEvaluationService evaluationService;
    private final ActionService actionService;
    private final ApprovalWaitRegistry waitRegistry;
    private final CredentialInjectionService credentialInjectionService;
    private final HttpClient httpClient;

    public AgentProxyService(
            HttpPolicyValidator validator,
            PolicyEvaluationService evaluationService,
            ActionService actionService,
            ApprovalWaitRegistry waitRegistry,
            CredentialInjectionService credentialInjectionService,
            ProxyProperties proxyProperties) {
        this.validator = validator;
        this.evaluationService = evaluationService;
        this.actionService = actionService;
        this.waitRegistry = waitRegistry;
        this.credentialInjectionService = credentialInjectionService;
        this.httpClient =
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(proxyProperties.connectTimeout())
                        .build();
    }

    public record ProxyResponse(
            int statusCode,
            Map<String, List<String>> headers,
            byte[] body,
            InputStream bodyStream) {

        public static ProxyResponse ofStream(int status, Map<String, List<String>> headers, InputStream stream) {
            return new ProxyResponse(status, headers, null, stream);
        }

        public static ProxyResponse jsonError(int status, String error, String message) {
            byte[] bytes =
                    JSON.writeValueAsBytes(
                            Map.of(
                                    "error", error,
                                    "message", message == null ? error : message));
            return new ProxyResponse(
                    status,
                    Map.of(HttpHeaders.CONTENT_TYPE, List.of(MediaType.APPLICATION_JSON_VALUE + "; charset=utf-8")),
                    bytes,
                    null);
        }
    }

    public record PreparedRequest(
            ProxyTarget target,
            URI targetUri,
            HttpMethod httpMethod,
            HttpHeaders cleanHeaders,
            boolean needsBody,
            EvaluationResult evaluationResult,
            ProxyResponse immediateError) {}

    public ProxyTarget parseConnectTarget(String rawAuthority) {
        ProxyTarget target = ProxyTarget.parseConnect(rawAuthority);
        String canonicalHost = validator.host(target.host());
        return target.withHost(canonicalHost);
    }

    public PreparedRequest prepareConnect(ProxyTarget target, HttpHeaders requestHeaders) {
        URI targetUri;
        HttpHeaders cleanHeaders;
        try {
            targetUri = target.toUri(null);
            cleanHeaders = sanitizeRequestHeaders(requestHeaders);
        } catch (IllegalArgumentException error) {
            return invalidRequest(error);
        }

        HttpMethod connect = HttpMethod.valueOf(io.netty.handler.codec.http.HttpMethod.CONNECT.name());
        HeaderEvaluationResult headerEval = evaluationService.evaluateHeaders(connect, targetUri, cleanHeaders);
        EvaluationResult result = headerEval.needsBody()
                ? evaluationService.evaluateWithBodyFile(connect, targetUri, cleanHeaders, null)
                : headerEval.finalResult();
        return finalizePreparedRequest(target, targetUri, connect, cleanHeaders, result);
    }

    /**
     * Waits for human approval if required and verifies the client is still connected before tunneling.
     *
     * @return {@code null} when authorized to connect upstream, or an error response to return to the client
     */
    public ProxyResponse authorizeTunnel(
            PreparedRequest prep,
            CompletableFuture<Void> clientDisconnected) {
        return authorizeExecution(prep.evaluationResult(), clientDisconnected);
    }

    private ProxyResponse authorizeExecution(
            EvaluationResult result,
            CompletableFuture<Void> clientDisconnected) {
        UUID actionId = result.action().getId();
        if (result.effect() == RuleEffect.ASK) {
            ProxyResponse waitError = awaitApproval(actionId, clientDisconnected);
            if (waitError != null) {
                return waitError;
            }
        }
        if (clientDisconnected.isDone()) {
            String reason = "Client closed connection before execution";
            actionService.updateStatus(actionId, ActionStatus.CANCELLED);
            return ProxyResponse.jsonError(HTTP_CLIENT_CLOSED_REQUEST, ERROR_CLIENT_DISCONNECTED, reason);
        }
        return null;
    }

    public PreparedRequest prepareForward(
            String method,
            String rawTarget,
            String rawQuery,
            HttpHeaders requestHeaders) {
        try {
            return prepareTarget(ProxyTarget.parseForwardRequest(rawTarget, rawQuery), method, rawQuery, requestHeaders);
        } catch (IllegalArgumentException error) {
            return invalidRequest(error);
        }
    }

    public PreparedRequest prepareTunnelRequest(
            ProxyTarget tunnelTarget,
            String method,
            String rawPath,
            String rawQuery,
            HttpHeaders requestHeaders) {
        try {
            return prepareTarget(
                    ProxyTarget.forTunnelRequest(tunnelTarget, rawPath, rawQuery),
                    method,
                    rawQuery,
                    requestHeaders);
        } catch (IllegalArgumentException error) {
            return invalidRequest(error);
        }
    }

    private PreparedRequest prepareTarget(
            ProxyTarget target,
            String method,
            String rawQuery,
            HttpHeaders requestHeaders) {
        HttpMethod httpMethod = validator.method(method);
        String canonicalHost = validator.host(target.host());
        ProxyTarget canonicalTarget = target.withHost(canonicalHost);
        validator.path(canonicalTarget.path());
        validator.query(rawQuery);
        URI targetUri = canonicalTarget.toUri(rawQuery);
        HttpHeaders cleanHeaders = sanitizeRequestHeaders(requestHeaders);

        HeaderEvaluationResult headerEval = evaluationService.evaluateHeaders(httpMethod, targetUri, cleanHeaders);
        if (headerEval.needsBody()) {
            return new PreparedRequest(canonicalTarget, targetUri, httpMethod, cleanHeaders, true, null, null);
        }
        return finalizePreparedRequest(canonicalTarget, targetUri, httpMethod, cleanHeaders, headerEval.finalResult());
    }

    private static PreparedRequest invalidRequest(IllegalArgumentException error) {
        return new PreparedRequest(
                null, null, null, null, false, null,
                ProxyResponse.jsonError(HttpStatus.BAD_REQUEST.value(), ERROR_INVALID_REQUEST, error.getMessage()));
    }

    private static PreparedRequest finalizePreparedRequest(
            ProxyTarget target,
            URI targetUri,
            HttpMethod method,
            HttpHeaders cleanHeaders,
            EvaluationResult result) {
        ProxyResponse denyError = result.effect() == RuleEffect.DENY
                ? ProxyResponse.jsonError(HttpStatus.FORBIDDEN.value(), ERROR_POLICY_DENIED, result.reason())
                : null;
        return new PreparedRequest(target, targetUri, method, cleanHeaders, false, result, denyError);
    }

    public ProxyResponse handleWithBodyFile(
            PreparedRequest prep,
            Path tempBodyFile,
            CompletableFuture<Void> clientDisconnected) {
        EvaluationResult decision = evaluationService.evaluateWithBodyFile(
                prep.httpMethod(), prep.targetUri(), prep.cleanHeaders(), tempBodyFile);

        if (decision.effect() == RuleEffect.DENY) {
            return ProxyResponse.jsonError(HttpStatus.FORBIDDEN.value(), ERROR_POLICY_DENIED, decision.reason());
        }

        try {
            HttpRequest.BodyPublisher publisher =
                    (tempBodyFile == null || !Files.exists(tempBodyFile) || Files.size(tempBodyFile) == 0)
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofFile(tempBodyFile);
            return forwardAuthorized(prep, decision, publisher, clientDisconnected);
        } catch (IOException e) {
            return ProxyResponse.jsonError(HttpStatus.INTERNAL_SERVER_ERROR.value(), ERROR_BODY_READ, "Failed reading request body file");
        }
    }

    public ProxyResponse handleStream(
            PreparedRequest prep,
            InputStream bodyStream,
            CompletableFuture<Void> clientDisconnected) {
        HttpRequest.BodyPublisher publisher =
                bodyStream == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofInputStream(() -> bodyStream);
        return forwardAuthorized(prep, prep.evaluationResult(), publisher, clientDisconnected);
    }

    private ProxyResponse forwardAuthorized(
            PreparedRequest prep,
            EvaluationResult decision,
            HttpRequest.BodyPublisher publisher,
            CompletableFuture<Void> clientDisconnected) {
        ProxyResponse authError = authorizeExecution(decision, clientDisconnected);
        if (authError != null) {
            return authError;
        }
        return forwardUpstream(
                decision.action().getId(),
                prep.target(),
                prep.targetUri(),
                prep.httpMethod().name(),
                prep.cleanHeaders(),
                publisher);
    }


    private ProxyResponse awaitApproval(
            UUID actionId, CompletableFuture<Void> clientDisconnected) {
        CompletableFuture<Void> waiter = waitRegistry.register(actionId);
        try {
            if (!clientDisconnected.isDone()) {
                ActionEntity current = actionService.getAction(actionId);
                if (current.getStatus() != ActionStatus.AWAITING_APPROVAL) {
                    return resolveOutcome(current);
                }
                CompletableFuture.anyOf(waiter, clientDisconnected).get();
            }
            if (clientDisconnected.isDone()) {
                expire(actionId, ActionStatus.CANCELLED);
                return ProxyResponse.jsonError(
                        HTTP_CLIENT_CLOSED_REQUEST,
                        ERROR_CLIENT_DISCONNECTED,
                        "Client closed connection before approval");
            }
            return resolveOutcome(actionService.getAction(actionId));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            expire(actionId, ActionStatus.CANCELLED);
            return ProxyResponse.jsonError(HttpStatus.SERVICE_UNAVAILABLE.value(), ERROR_INTERRUPTED, "Request interrupted");
        } catch (ExecutionException error) {
            return ProxyResponse.jsonError(HttpStatus.INTERNAL_SERVER_ERROR.value(), ERROR_APPROVAL_ERROR, "Failed waiting for approval");
        } finally {
            waitRegistry.unregister(actionId, waiter);
        }
    }

    private ProxyResponse resolveOutcome(ActionEntity action) {
        return switch (action.getStatus()) {
            case ALLOWED -> null; // Proceed to forward!
            case DENIED ->
                    ProxyResponse.jsonError(HttpStatus.FORBIDDEN.value(), ERROR_APPROVAL_REJECTED, "Human approval rejected");
            case EXPIRED ->
                    ProxyResponse.jsonError(HttpStatus.CONFLICT.value(), ERROR_APPROVAL_EXPIRED, "Approval expired before completion");
            case CANCELLED ->
                    ProxyResponse.jsonError(HTTP_CLIENT_CLOSED_REQUEST, ERROR_CLIENT_DISCONNECTED, "Client closed connection before approval");
            case AWAITING_APPROVAL, FAILED ->
                    ProxyResponse.jsonError(HttpStatus.INTERNAL_SERVER_ERROR.value(), ERROR_APPROVAL_PENDING, "Approval still pending");
        };
    }

    private void expire(UUID actionId, ActionStatus status) {
        try {
            actionService.expirePendingAction(actionId, status);
        } catch (Exception ignored) {
        }
    }

    public ProxyResponse forwardUpstream(
            UUID actionId,
            ProxyTarget target,
            URI targetUri,
            String method,
            HttpHeaders cleanHeaders,
            HttpRequest.BodyPublisher publisher) {
        CredentialInjectionService.InjectedTarget injected =
                credentialInjectionService.inject(target.host(), targetUri, cleanHeaders);
        URI effectiveUri = injected.targetUri();
        HttpHeaders effectiveHeaders = injected.headers();

        HttpRequest.Builder builder =
                HttpRequest.newBuilder(effectiveUri)
                        .method(method, publisher);

        effectiveHeaders.forEach(
                (name, values) -> {
                    if (isFilteredRequestHeader(name)) {
                        return;
                    }
                    for (String value : values) {
                        builder.header(name, value);
                    }
                });

        try {
            HttpResponse<InputStream> upstream =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());

            return ProxyResponse.ofStream(
                    upstream.statusCode(),
                    copyResponseHeaders(upstream),
                    upstream.body());
        } catch (SSLException sslError) {
            return upstreamFailure(actionId, ActionStatus.FAILED, HttpStatus.BAD_GATEWAY, ERROR_UPSTREAM_TLS_FAILED, "Upstream TLS handshake failed");
        } catch (ConnectException connectError) {
            return upstreamFailure(actionId, ActionStatus.FAILED, HttpStatus.BAD_GATEWAY, ERROR_UPSTREAM_CONNECT_FAILED, "Could not connect to upstream host");
        } catch (IOException ioError) {
            return upstreamFailure(actionId, ActionStatus.FAILED, HttpStatus.BAD_GATEWAY, ERROR_UPSTREAM_IO_FAILED, "Upstream request failed or timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return upstreamFailure(actionId, ActionStatus.CANCELLED, HttpStatus.SERVICE_UNAVAILABLE, ERROR_INTERRUPTED, "Upstream request interrupted");
        }
    }

    private ProxyResponse upstreamFailure(
            UUID actionId,
            ActionStatus actionStatus,
            HttpStatus httpStatus,
            String errorCode,
            String reason) {
        actionService.updateStatus(actionId, actionStatus);
        return ProxyResponse.jsonError(httpStatus.value(), errorCode, reason);
    }

    private static Map<String, List<String>> copyResponseHeaders(HttpResponse<InputStream> upstream) {
        Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> header : upstream.headers().map().entrySet()) {
            String lower = header.getKey().toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP_HEADERS.contains(lower) || lower.startsWith(":")) {
                continue;
            }
            responseHeaders.put(header.getKey(), new ArrayList<>(header.getValue()));
        }
        return responseHeaders;
    }

    private static boolean isFilteredRequestHeader(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return HOP_BY_HOP_HEADERS.contains(lower) || lower.startsWith(WHITEKEY_HEADER_PREFIX);
    }

    private static HttpHeaders sanitizeRequestHeaders(HttpHeaders headers) {
        HttpHeaders clean = new HttpHeaders();
        headers.forEach(
                (name, values) -> {
                    if (!isFilteredRequestHeader(name)) {
                        clean.put(name, List.copyOf(values));
                    }
                });
        return clean;
    }
}
