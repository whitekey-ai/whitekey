package ai.whitekey.service;

import ai.whitekey.entity.ActionEntity;

public interface ProtocolApprovalHandler {
    boolean supports(ActionEntity action);
    void validateApproval(ActionEntity action, boolean saveRule);
    void saveAllowRule(ActionEntity action);
}
