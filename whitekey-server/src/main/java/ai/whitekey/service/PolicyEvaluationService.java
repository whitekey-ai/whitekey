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

    public record HeaderEvaluationResult(
            boolean needsBody,
            EvaluationResult finalResult,
            List<HttpRuleEntity> candidateRules) {}

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
            return new HeaderEvaluationResult(true, null, headerEval.candidateRules());
        }

        return new HeaderEvaluationResult(
                false,
                recordDecision(method, url, headers, null, headerEval.decision()),
                headerEval.candidateRules());
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

        if (!setup && decision.effect() != RuleEffect.REQUIRE_APPROVAL) {
            ActionStatus status = decision.effect() == RuleEffect.ALLOW ? ActionStatus.ALLOWED : ActionStatus.DENIED;
            HttpActionEntity action = actions.recordHttp(method, url, headers, bodyBytes, status);
            return new EvaluationResult(decision.effect(), decision.reason(), action);
        }

        String reason = setup ? SETUP_APPROVAL_REASON : decision.reason();
        HttpActionEntity action = actions.recordHttp(method, url, headers, bodyBytes, ActionStatus.AWAITING_APPROVAL);
        return new EvaluationResult(RuleEffect.REQUIRE_APPROVAL, reason, action);
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
