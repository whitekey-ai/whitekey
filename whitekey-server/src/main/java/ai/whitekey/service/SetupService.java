package ai.whitekey.service;

import ai.whitekey.entity.ActionEntity;
import ai.whitekey.entity.ActionStatus;
import ai.whitekey.entity.HttpActionEntity;
import ai.whitekey.entity.SetupStateEntity;
import ai.whitekey.policy.PolicyDecisionEngine;
import ai.whitekey.repository.ActionRepository;
import ai.whitekey.repository.SetupStateRepository;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SetupService {

    private final SetupStateRepository setupRepository;
    private final ActionRepository actionRepository;
    private final RuleService rules;
    private final PolicyDecisionEngine engine;
    private final ApprovalWaitRegistry waitRegistry;

    public SetupService(
            SetupStateRepository setupRepository,
            ActionRepository actionRepository,
            RuleService rules,
            PolicyDecisionEngine engine,
            ApprovalWaitRegistry waitRegistry
    ) {
        this.setupRepository = setupRepository;
        this.actionRepository = actionRepository;
        this.rules = rules;
        this.engine = engine;
        this.waitRegistry = waitRegistry;
    }

    @Transactional(readOnly = true)
    public boolean isSetupMode() {
        return setupRepository
                .findById(SetupStateEntity.GLOBAL_ID)
                .map(SetupStateEntity::isSetupMode)
                .orElse(false);
    }

    @Transactional
    public void start() {
        var setup = setupRepository
                        .findById(SetupStateEntity.GLOBAL_ID)
                        .orElseGet(() -> new SetupStateEntity(false));
        if (setup.isSetupMode()) {
            return;
        }
        setup.setSetupMode(true);
        setupRepository.saveAndFlush(setup);
    }

    @Transactional
    public void finish() {
        var setup = setupRepository
                        .findById(SetupStateEntity.GLOBAL_ID)
                        .orElseGet(() -> new SetupStateEntity(false));
        setup.setSetupMode(false);
        setupRepository.save(setup);

        var currentRules = rules.getHttpRules();
        var pending = actionRepository.findByStatusOrderByIdAsc(ActionStatus.AWAITING_APPROVAL, Pageable.unpaged());
        for (ActionEntity action : pending) {
            if (action instanceof HttpActionEntity http && engine.evaluateHttp(http, currentRules).isUnmatchedAction()) {
                int changed = actionRepository.resolvePending(
                        http.getId(),
                        ActionStatus.EXPIRED);
                if (changed > 0) {
                    waitRegistry.notifyResolved(http.getId());
                }
            }
        }
    }
}
