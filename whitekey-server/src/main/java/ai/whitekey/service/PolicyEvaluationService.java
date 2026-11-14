package ai.whitekey.service;

import ai.whitekey.entity.ActionStatus;
import ai.whitekey.entity.HttpActionEntity;
import ai.whitekey.entity.HttpRuleEntity;
import ai.whitekey.entity.RuleEffect;
import ai.whitekey.policy.PolicyDecisionEngine;
import ai.whitekey.policy.PolicyDecisionEngine.Decision;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
public class PolicyEvaluationService {
    private static final String SETUP_APPROVAL_REASON = "Setup connection requires approval";

    public record EvaluationResult(RuleEffect effect, String reason, HttpActionEntity action) {}

    public record HeaderEvaluationResult(boolean needsBody, EvaluationResult finalResult) {}

    private final RuleService rules;
    private final ActionService actions;
    private final SetupService setupService;
    private final PolicyDecisionEngine engine;

    public PolicyEvaluationService(RuleService rules, ActionService actions, SetupService setupService, PolicyDecisionEngine engine) {
        this.rules = rules;
        this.actions = actions;
        this.setupService = setupService;
        this.engine = engine;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public HeaderEvaluationResult evaluateHeaders(
            HttpMethod method,
            URI url,
            HttpHeaders headers) {
        List<HttpRuleEntity> applicableRules = rules.getHttpRules();
        PolicyDecisionEngine.HeaderEvaluation headerEval =
                engine.evaluateHeaders(method, url, headers, applicableRules);

        if (headerEval.needsBody()) {
            return new HeaderEvaluationResult(true, null);
        }

        return new HeaderEvaluationResult(
                false,
                recordDecision(method, url, headers, null, headerEval.decision()));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public EvaluationResult evaluateWithBodyFile(
            HttpMethod method,
            URI url,
            HttpHeaders headers,
            Path bodyFile) {
        List<HttpRuleEntity> applicableRules = rules.getHttpRules();
        Decision decision = engine.evaluateWithBodyFile(method, url, headers, bodyFile, applicableRules);
        byte[] bodyBytes = readBodySilently(bodyFile);
        return recordDecision(method, url, headers, bodyBytes, decision);
    }

    private EvaluationResult recordDecision(
            HttpMethod method,
            URI url,
            HttpHeaders headers,
            byte[] bodyBytes,
            Decision decision) {
        boolean setup = setupService.isSetupMode() && decision.isUnmatchedAction();
        RuleEffect effect = setup ? RuleEffect.REQUIRE_APPROVAL : decision.effect();
        String reason = setup ? SETUP_APPROVAL_REASON : decision.reason();
        ActionStatus status = switch (effect) {
            case ALLOW -> ActionStatus.ALLOWED;
            case DENY -> ActionStatus.DENIED;
            case REQUIRE_APPROVAL -> ActionStatus.AWAITING_APPROVAL;
        };
        HttpActionEntity action = actions.recordHttp(method, url, headers, bodyBytes, status);
        return new EvaluationResult(effect, reason, action);
    }

    private static byte[] readBodySilently(Path bodyFile) {
        if (bodyFile == null || !Files.exists(bodyFile)) {
            return null;
        }
        try {
            return Files.readAllBytes(bodyFile);
        } catch (IOException e) {
            return null;
        }
    }
}
