package ai.whitekey.service;

import ai.whitekey.entity.ActionEntity;
import ai.whitekey.entity.ApprovalRequestEntity;

public interface ProtocolApprovalHandler {
    boolean supports(ActionEntity action);
    ApprovalRequestEntity createApprovalRequest(ActionEntity action);
    void validateApproval(ActionEntity action, boolean saveRule);
    void saveAllowRule(ActionEntity action);
}
