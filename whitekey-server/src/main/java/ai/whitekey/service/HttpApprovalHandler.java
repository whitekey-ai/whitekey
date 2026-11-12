package ai.whitekey.service;

import ai.whitekey.entity.ActionEntity;
import ai.whitekey.entity.ApprovalRequestEntity;
import ai.whitekey.entity.HttpActionEntity;
import ai.whitekey.entity.HttpApprovalRequestEntity;
import ai.whitekey.entity.RuleEffect;
import ai.whitekey.exception.ApprovalConflictException;
import ai.whitekey.policy.PolicyDecisionEngine;
import ai.whitekey.proxy.ProxyTarget;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class HttpApprovalHandler implements ProtocolApprovalHandler {

    private final RuleService rules;
    private final SetupService setupService;
    private final PolicyDecisionEngine engine;

    public HttpApprovalHandler(RuleService rules, SetupService setupService, PolicyDecisionEngine engine) {
        this.rules = rules;
        this.setupService = setupService;
        this.engine = engine;
    }

    @Override
    public boolean supports(ActionEntity action) {
        return action instanceof HttpActionEntity;
    }

    @Override
    public ApprovalRequestEntity createApprovalRequest(ActionEntity action) {
        return new HttpApprovalRequestEntity(requireHttp(action));
    }

    @Override
    public void validateApproval(ActionEntity action, boolean saveRule) {
        HttpActionEntity http = requireHttp(action);
        var currentRules = rules.getHttpRules();
        var current = engine.evaluateHttp(http, currentRules);
        if (current.isUnmatchedAction() && !setupService.isSetupMode()) {
            throw new ApprovalConflictException("Setup mode has ended");
        }
        if (saveRule
                && current.effect() == RuleEffect.DENY
                && !current.isUnmatchedAction()) {
            throw new ApprovalConflictException(PolicyDecisionEngine.RULE_DENIED_REASON);
        }
    }

    @Override
    public void saveAllowRule(ActionEntity action) {
        HttpActionEntity http = requireHttp(action);
        HttpMethod method = http.getMethod();
        String host = http.getHost();
        int port = http.getPort();
        String path = http.getPath();
        boolean exists =
                rules.getHttpRules().stream()
                        .anyMatch(
                                rule ->
                                        rule.getEffect() == RuleEffect.ALLOW
                                                && rule.getMethods().contains(method)
                                                && rule.getHosts().equals(List.of(host))
                                                && (rule.getPorts().isEmpty()
                                                        || rule.getPorts().contains(port))
                                                && rule.getPaths().equals(List.of(path)));
        if (!exists) {
            rules.createHttpRule(
                    RuleEffect.ALLOW,
                    List.of(method),
                    List.of(host),
                    (port == ProxyTarget.DEFAULT_HTTP_PORT || port == ProxyTarget.DEFAULT_HTTPS_PORT) ? List.of() : List.of(port),
                    List.of(path),
                    Map.of());
        }
    }

    private static HttpActionEntity requireHttp(ActionEntity action) {
        if (action instanceof HttpActionEntity http) {
            return http;
        }
        throw new IllegalArgumentException("Action must be an HttpActionEntity: " + action);
    }
}
