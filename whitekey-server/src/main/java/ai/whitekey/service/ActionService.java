package ai.whitekey.service;

import ai.whitekey.api.dto.ProtocolConstants;
import ai.whitekey.entity.ActionEntity;
import ai.whitekey.entity.ActionStatus;
import ai.whitekey.entity.HttpActionEntity;
import ai.whitekey.exception.ResourceNotFoundException;
import ai.whitekey.proxy.ProxyTarget;
import ai.whitekey.repository.ActionRepository;
import ai.whitekey.repository.ApprovalRequestRepository;
import ai.whitekey.repository.HttpActionRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ActionService {

    public static final String DEFAULT_MAX_STORED_BODY_SIZE = "64KB";
    public static final int DEFAULT_MAX_STORED_BODY_BYTES = (int) DataSize.ofKilobytes(64).toBytes();

    private final ActionRepository actionRepository;
    private final HttpActionRepository httpActionRepository;
    private final ApprovalRequestRepository approvalRepository;
    private final int maxStoredBodyBytes;

    public ActionService(
            ActionRepository actionRepository,
            HttpActionRepository httpActionRepository,
            ApprovalRequestRepository approvalRepository,
            @Value("${app.audit.max-body-bytes:" + DEFAULT_MAX_STORED_BODY_SIZE + "}") DataSize maxStoredBodySize) {
        this.actionRepository = actionRepository;
        this.httpActionRepository = httpActionRepository;
        this.approvalRepository = approvalRepository;
        this.maxStoredBodyBytes = (int) (maxStoredBodySize != null ? maxStoredBodySize.toBytes() : DEFAULT_MAX_STORED_BODY_BYTES);
    }

    @Transactional
    public HttpActionEntity recordHttp(
            HttpMethod method,
            URI url,
            HttpHeaders headers,
            byte[] body,
            ActionStatus status,
            String reason) {
        int port = (url != null && url.getPort() != -1)
                ? url.getPort()
                : (url != null && ProtocolConstants.HTTPS.equalsIgnoreCase(url.getScheme())
                        ? ProxyTarget.DEFAULT_HTTPS_PORT
                        : ProxyTarget.DEFAULT_HTTP_PORT);
        String host = (url != null && url.getHost() != null)
                ? url.getHost().toLowerCase(Locale.ROOT)
                : "";
        String rawPath = (url != null) ? url.getRawPath() : null;
        String path = (rawPath != null && !rawPath.isEmpty()) ? rawPath : "/";
        Map<String, List<String>> queryParams = (url != null && url.getRawQuery() != null && !url.getRawQuery().isBlank())
                ? UriComponentsBuilder.fromUri(url).build().getQueryParams()
                : Collections.emptyMap();
        HttpHeaders safeHeaders = headers != null ? HttpHeaders.readOnlyHttpHeaders(headers) : HttpHeaders.EMPTY;
        byte[] safeBody = body != null ? body : new byte[0];
        if (safeBody.length > maxStoredBodyBytes) {
            safeBody = Arrays.copyOf(safeBody, maxStoredBodyBytes);
        }

        HttpActionEntity entity = new HttpActionEntity(
                method,
                url != null ? url.toString() : "",
                host,
                port,
                path,
                queryParams,
                safeHeaders,
                safeBody,
                status,
                reason);
        return httpActionRepository.save(entity);
    }

    @Transactional
    public void updateStatus(UUID id, ActionStatus status, String reason) {
        actionRepository.updateStatus(id, status, reason);
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
        var pageable = PageRequests.of(page, limit);
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

    @Transactional
    public void clearFinished() {
        approvalRepository.deleteResolved();
        List<ActionEntity> finished = actionRepository.findByStatusNot(ActionStatus.AWAITING_APPROVAL);
        if (!finished.isEmpty()) {
            actionRepository.deleteAll(finished);
        }
    }
}
