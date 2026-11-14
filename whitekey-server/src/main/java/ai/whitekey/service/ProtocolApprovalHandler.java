package ai.whitekey.service;

import ai.whitekey.entity.ActionEntity;

public interface ProtocolApprovalHandler<T extends ActionEntity> {

    Class<T> getActionType();

    void validateApproval(T action, boolean saveRule);

    void saveAllowRule(T action);

    default boolean supports(ActionEntity action) {
        return getActionType().isInstance(action);
    }

    default void validate(ActionEntity action, boolean saveRule) {
        validateApproval(getActionType().cast(action), saveRule);
    }

    default void saveRule(ActionEntity action) {
        saveAllowRule(getActionType().cast(action));
    }
}
