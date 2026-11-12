package ai.whitekey.service;

import ai.whitekey.entity.ActionEntity;
import ai.whitekey.entity.ActionStatus;
import ai.whitekey.entity.ApprovalRequestEntity;
import ai.whitekey.entity.ApprovalRequestStatus;
import ai.whitekey.exception.ApprovalConflictException;
import ai.whitekey.exception.ResourceNotFoundException;
import ai.whitekey.repository.ActionRepository;
import ai.whitekey.repository.ApprovalRequestRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ApprovalRequestService {

    private final ApprovalRequestRepository repository;
    private final ActionRepository actionRepository;
    private final ApprovalWaitRegistry waitRegistry;
    private final List<ProtocolApprovalHandler> protocolHandlers;

    public ApprovalRequestService(
            ApprovalRequestRepository repository,
            ActionRepository actionRepository,
            ApprovalWaitRegistry waitRegistry,
            List<ProtocolApprovalHandler> protocolHandlers) {
        this.repository = repository;
        this.actionRepository = actionRepository;
        this.waitRegistry = waitRegistry;
        this.protocolHandlers = protocolHandlers;
    }

    @Transactional
    public ApprovalRequestEntity createRequest(ActionEntity actionEntity) {
        for (ProtocolApprovalHandler handler : protocolHandlers) {
            if (handler.supports(actionEntity)) {
                return repository.save(handler.createApprovalRequest(actionEntity));
            }
        }
        throw new IllegalArgumentException("No protocol approval handler found for action entity: " + actionEntity.getClass().getName());
    }

    @Transactional(readOnly = true)
    public ApprovalRequestEntity getRequest(UUID id) {
        return findRequest(id);
    }

    @Transactional(readOnly = true)
    public List<ApprovalRequestEntity> getRequests(int page, int limit, boolean pending) {
        var pageable = PageRequests.of(page, limit);
        return pending
                ? repository.findByStatusOrderByIdAsc(
                        ApprovalRequestStatus.PENDING, pageable)
                : repository.findAllByOrderByIdDesc(pageable);
    }

    @Transactional
    public ApprovalRequestEntity resolveRequest(
            UUID id, ApprovalRequestStatus decision, boolean saveRule) {
        if (decision != ApprovalRequestStatus.APPROVED
                && decision != ApprovalRequestStatus.REJECTED) {
            throw new IllegalArgumentException("Decision must be APPROVED or REJECTED");
        }
        if (saveRule && decision != ApprovalRequestStatus.APPROVED) {
            throw new IllegalArgumentException("Only approval can save an allow rule");
        }
        ApprovalRequestEntity pending = findRequest(id);
        ActionEntity actionEntity = pending.getAction();
        if (decision == ApprovalRequestStatus.APPROVED) {
            validateApproval(actionEntity, saveRule);
        }
        int changed = repository.updatePendingStatus(id, decision);
        if (changed == 0) {
            throw new ApprovalConflictException(
                    "This request is no longer pending. Refresh and try again.");
        }
        ActionStatus nextActionStatus =
                decision == ApprovalRequestStatus.APPROVED
                        ? ActionStatus.ALLOWED
                        : ActionStatus.DENIED;
        actionRepository.updateStatus(actionEntity.getId(), nextActionStatus, decision.message());
        if (saveRule) {
            saveAllowRule(actionEntity);
        }
        ApprovalRequestEntity updated = findRequest(id);
        waitRegistry.notifyResolved(id, decision);
        return updated;
    }

    @Transactional
    public void expireUnexecutedRequest(UUID id, ApprovalRequestStatus status) {
        if (status == null || !status.isExpired()) {
            throw new IllegalArgumentException("Expired status is required");
        }
        ApprovalRequestEntity pending = findRequest(id);
        int changed = repository.updatePendingStatus(id, status);
        if (changed > 0) {
            ActionStatus nextActionStatus =
                    status == ApprovalRequestStatus.CLIENT_DISCONNECTED
                            ? ActionStatus.CANCELLED
                            : ActionStatus.DENIED;
            actionRepository.updateStatus(
                    pending.getAction().getId(), nextActionStatus, status.message());
            waitRegistry.notifyResolved(id, status);
        }
    }

    private ApprovalRequestEntity findRequest(UUID id) {
        return repository
                .findById(id)
                .orElseThrow(() -> ResourceNotFoundException.forApprovalRequest(id));
    }

    private void validateApproval(ActionEntity actionEntity, boolean saveRule) {
        for (ProtocolApprovalHandler handler : protocolHandlers) {
            if (handler.supports(actionEntity)) {
                handler.validateApproval(actionEntity, saveRule);
                return;
            }
        }
    }

    private void saveAllowRule(ActionEntity actionEntity) {
        for (ProtocolApprovalHandler handler : protocolHandlers) {
            if (handler.supports(actionEntity)) {
                handler.saveAllowRule(actionEntity);
                return;
            }
        }
    }
}
