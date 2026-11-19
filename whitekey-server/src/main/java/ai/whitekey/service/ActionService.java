package ai.whitekey.service;

import ai.whitekey.api.dto.ProtocolConstants;
import ai.whitekey.entity.ActionEntity;
import ai.whitekey.entity.ActionStatus;
import ai.whitekey.entity.HttpActionEntity;
import ai.whitekey.exception.ApprovalConflictException;
import ai.whitekey.exception.ResourceNotFoundException;
import ai.whitekey.policy.HttpPolicyValidator;
import ai.whitekey.proxy.ProxyTarget;
import ai.whitekey.repository.ActionRepository;
import ai.whitekey.repository.HttpActionRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ActionService {

    public static final String DEFAULT_MAX_STORED_BODY_SIZE = "64KB";

    private final ActionRepository actionRepository;
    private final HttpActionRepository httpActionRepository;
    private final ApprovalWaitRegistry waitRegistry;
    private final List<ProtocolApprovalHandler<?>> protocolHandlers;
    private final int maxStoredBodyBytes;

    public ActionService(
            ActionRepository actionRepository,
            HttpActionRepository httpActionRepository,
            ApprovalWaitRegistry waitRegistry,
            List<ProtocolApprovalHandler<?>> protocolHandlers,
            @Value("${app.audit.max-body-bytes:" + DEFAULT_MAX_STORED_BODY_SIZE + "}") DataSize maxStoredBodySize) {
        this.actionRepository = actionRepository;
        this.httpActionRepository = httpActionRepository;
        this.waitRegistry = waitRegistry;
        this.protocolHandlers = protocolHandlers;
        this.maxStoredBodyBytes = (int) maxStoredBodySize.toBytes();
    }

    public int getMaxStoredBodyBytes() {
        return maxStoredBodyBytes;
    }

    @Transactional
    public HttpActionEntity recordHttp(
            HttpMethod method,
            URI url,
            HttpHeaders headers,
            byte[] body,
            ActionStatus status) {
        return recordHttp(method, url, headers, body, false, status);
    }

    @Transactional
    public HttpActionEntity recordHttp(
            HttpMethod method,
            URI url,
            HttpHeaders headers,
            byte[] body,
            boolean bodyTruncated,
            ActionStatus status) {
        ProxyTarget target = ProxyTarget.fromUri(url);
        Map<String, List<String>> queryParams = HttpPolicyValidator.parseQueryParams(target.rawQuery());
        HttpHeaders safeHeaders = headers != null ? HttpHeaders.readOnlyHttpHeaders(headers) : HttpHeaders.EMPTY;
        byte[] safeBody = body != null ? body : new byte[0];
        boolean truncated = bodyTruncated;
        if (safeBody.length > maxStoredBodyBytes) {
            safeBody = Arrays.copyOf(safeBody, maxStoredBodyBytes);
            truncated = true;
        }

        HttpActionEntity entity = new HttpActionEntity(
                method,
                url != null ? url.toString() : "",
                target.host(),
                target.port(),
                target.path(),
                queryParams,
                safeHeaders,
                safeBody,
                status);
        entity.setBodyTruncated(truncated);
        return httpActionRepository.save(entity);
    }

    @Transactional
    public void updateStatus(UUID id, ActionStatus status) {
        actionRepository.updateStatus(id, status);
    }

    @Transactional(readOnly = true)
    public ActionEntity getAction(UUID id) {
        return actionRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.forAction(id));
    }

    @Transactional(readOnly = true)
    public List<ActionEntity> getActions(
            int page,
            int limit,
            String type,
            ActionStatus status,
            String method,
            String host) {
        if (page < 0 || limit < 1) {
            throw new IllegalArgumentException("Page must be >= 0 and limit >= 1");
        }
        var pageable = PageRequest.of(page, limit);
        String normalizedType = (type == null || type.isBlank()) ? null : type.trim().toLowerCase(Locale.ROOT);
        HttpMethod normalizedMethod = (method == null || method.isBlank()) ? null : HttpMethod.valueOf(method.trim().toUpperCase(Locale.ROOT));
        String normalizedHost = (host == null || host.isBlank()) ? null : host.trim().toLowerCase(Locale.ROOT);

        if (normalizedType == null && normalizedMethod == null && normalizedHost == null) {
            return status == null ? actionRepository.findAllByOrderByIdDesc(pageable) : actionRepository.findByStatusOrderByIdDesc(status, pageable);
        }

        if (normalizedType != null && !ProtocolConstants.HTTP.equals(normalizedType)) {
            throw new IllegalArgumentException("Unsupported action type: " + type);
        }

        return List.copyOf(httpActionRepository
                .findFiltered(status, normalizedMethod, normalizedHost, pageable));
    }

    @Transactional(readOnly = true)
    public int countPendingApprovals() {
        return (int) actionRepository.countByStatus(ActionStatus.AWAITING_APPROVAL);
    }

    @Transactional
    public ActionEntity resolveAction(UUID id, String decision, boolean saveRule) {
        ActionStatus nextStatus = parseDecision(decision);
        if (saveRule && nextStatus != ActionStatus.ALLOWED) {
            throw new IllegalArgumentException("Only approval can save an allow rule");
        }
        ActionEntity action = getAction(id);
        if (action.getStatus() != ActionStatus.AWAITING_APPROVAL) {
            throw new ApprovalConflictException(
                    "This action is no longer awaiting approval. Refresh and try again.");
        }
        ProtocolApprovalHandler<?> handler = protocolHandlers.stream()
                .filter(h -> h.supports(action))
                .findFirst()
                .orElse(null);
        if (nextStatus == ActionStatus.ALLOWED && handler != null) {
            handler.validate(action, saveRule);
        }
        if (actionRepository.resolvePending(id, nextStatus) == 0) {
            throw new ApprovalConflictException(
                    "This action is no longer awaiting approval. Refresh and try again.");
        }
        if (saveRule && handler != null) {
            handler.saveRule(action);
        }
        action.setStatus(nextStatus);
        waitRegistry.notifyResolved(id);
        return action;
    }

    @Transactional
    public void expirePendingAction(UUID id, ActionStatus status) {
        if (status != ActionStatus.CANCELLED && status != ActionStatus.EXPIRED) {
            throw new IllegalArgumentException("Expired or cancelled status is required");
        }
        if (actionRepository.resolvePending(id, status) > 0) {
            waitRegistry.notifyResolved(id);
        }
    }

    @Transactional
    public void clearFinished() {
        List<ActionEntity> finished = actionRepository.findByStatusNot(ActionStatus.AWAITING_APPROVAL);
        if (!finished.isEmpty()) {
            actionRepository.deleteAll(finished);
        }
    }

    private static ActionStatus parseDecision(String decision) {
        if (decision == null || decision.isBlank()) {
            throw new IllegalArgumentException("Decision must not be blank");
        }
        return switch (decision.trim().toUpperCase(Locale.ROOT)) {
            case "ALLOW", "APPROVED", "ALLOWED" -> ActionStatus.ALLOWED;
            case "DENY", "DENIED", "REJECTED" -> ActionStatus.DENIED;
            default -> throw new IllegalArgumentException("Invalid approval decision: " + decision);
        };
    }
}
