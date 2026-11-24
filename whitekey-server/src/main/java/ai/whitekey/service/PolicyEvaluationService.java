package ai.whitekey.service;

import ai.whitekey.api.dto.RuleEffect;
import ai.whitekey.entity.ActionStatus;
import ai.whitekey.entity.HttpActionEntity;
import ai.whitekey.entity.HttpRuleEntity;
import ai.whitekey.policy.PolicyDecisionEngine;
import ai.whitekey.policy.PolicyDecisionEngine.Decision;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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

        if (headerEval.needsBody() || resolvesToAsk(headerEval.decision())) {
            return new HeaderEvaluationResult(true, null);
        }

        return new HeaderEvaluationResult(
                false,
                recordDecision(method, url, headers, null, false, headerEval.decision()));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public EvaluationResult evaluateWithBodyFile(
            HttpMethod method,
            URI url,
            HttpHeaders headers,
            Path bodyFile) {
        List<HttpRuleEntity> applicableRules = rules.getHttpRules();
        Decision decision = engine.evaluateWithBodyFile(method, url, headers, bodyFile, applicableRules);
        AuditBody preview = readAuditBody(bodyFile, actions.getMaxStoredBodyBytes());
        return recordDecision(method, url, headers, preview.bytes(), preview.truncated(), decision);
    }

    private record AuditBody(byte[] bytes, boolean truncated) {}

    private boolean resolvesToAsk(Decision decision) {
        if (decision == null) {
            return false;
        }
        return isSetupApproval(decision) || decision.effect() == RuleEffect.ASK;
    }

    private boolean isSetupApproval(Decision decision) {
        return setupService.isSetupMode() && decision.isUnmatchedAction();
    }

    private EvaluationResult recordDecision(
            HttpMethod method,
            URI url,
            HttpHeaders headers,
            byte[] bodyBytes,
            boolean bodyTruncated,
            Decision decision) {
        boolean setup = isSetupApproval(decision);
        RuleEffect effect = setup ? RuleEffect.ASK : decision.effect();
        String reason = setup ? SETUP_APPROVAL_REASON : decision.reason();
        ActionStatus status = switch (effect) {
            case ALLOW -> ActionStatus.ALLOWED;
            case DENY -> ActionStatus.DENIED;
            case ASK -> ActionStatus.PENDING;
        };
        HttpActionEntity action = actions.recordHttp(method, url, headers, bodyBytes, bodyTruncated, status);
        return new EvaluationResult(effect, reason, action);
    }

    private static AuditBody readAuditBody(Path bodyFile, int maxBytes) {
        if (bodyFile == null || maxBytes <= 0 || !Files.exists(bodyFile)) {
            return new AuditBody(null, false);
        }
        try (InputStream in = Files.newInputStream(bodyFile)) {
            byte[] chunk = in.readNBytes(maxBytes + 1);
            if (chunk.length > maxBytes) {
                return new AuditBody(Arrays.copyOf(chunk, maxBytes), true);
            }
            return new AuditBody(chunk, false);
        } catch (IOException e) {
            return new AuditBody(null, false);
        }
    }
}
