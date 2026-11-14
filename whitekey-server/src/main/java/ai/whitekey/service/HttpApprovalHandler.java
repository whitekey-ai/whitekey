package ai.whitekey.service;

import ai.whitekey.entity.HttpActionEntity;
import ai.whitekey.entity.RuleEffect;
import ai.whitekey.exception.ApprovalConflictException;
import ai.whitekey.policy.PolicyDecisionEngine;
import ai.whitekey.proxy.ProxyTarget;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class HttpApprovalHandler implements ProtocolApprovalHandler<HttpActionEntity> {

    private final RuleService rules;
    private final SetupService setupService;
    private final PolicyDecisionEngine engine;

    public HttpApprovalHandler(RuleService rules, SetupService setupService, PolicyDecisionEngine engine) {
        this.rules = rules;
        this.setupService = setupService;
        this.engine = engine;
    }

    @Override
    public Class<HttpActionEntity> getActionType() {
        return HttpActionEntity.class;
    }

    @Override
    public void validateApproval(HttpActionEntity http, boolean saveRule) {
        var current = engine.evaluateHttp(http, rules.getHttpRules());
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
    public void saveAllowRule(HttpActionEntity http) {
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
            String baseName = (host != null && !host.isBlank()) ? "allow_" + host : "allow_rule";
            // Scope CONNECT tunnel rules explicitly to host + port (e.g. 443)
            List<Integer> rulePorts = ("CONNECT".equalsIgnoreCase(method.name())
                    || (port != ProxyTarget.DEFAULT_HTTP_PORT && port != ProxyTarget.DEFAULT_HTTPS_PORT))
                    ? List.of(port)
                    : List.of();
            rules.createAutoHttpRule(
                    baseName,
                    RuleEffect.ALLOW,
                    List.of(method),
                    List.of(host),
                    rulePorts,
                    List.of(path),
                    Map.of());
        }
    }
}
