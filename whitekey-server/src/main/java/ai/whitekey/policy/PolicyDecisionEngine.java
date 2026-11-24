package ai.whitekey.policy;

import ai.whitekey.api.dto.RuleEffect;
import ai.whitekey.config.PolicyProperties;
import ai.whitekey.entity.HttpActionEntity;
import ai.whitekey.entity.HttpRuleEntity;
import ai.whitekey.proxy.ProxyTarget;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

@Component
public final class PolicyDecisionEngine {
    public static final String RULE_DENIED_REASON = "A matching rule explicitly denies this action";
    private static final String NO_MATCHING_RULE_REASON = "No applicable rule allows this action";
    private static final String NO_MATCHING_RULE_ALLOW_REASON = "No matching rule; allowed by default policy";
    private static final String NO_MATCHING_RULE_APPROVAL_REASON = "No matching rule; requires human approval by default policy";
    private static final String RULE_REQUIRES_APPROVAL_REASON = "A matching rule requires human approval";
    private static final String RULE_ALLOWED_REASON = "Matching rules allow this action";
    private static final String INVALID_BODY_REASON = "Request body is not valid JSON or contains duplicate keys";

    private final CelBodyEvaluator bodyEvaluator;
    private final RuleEffect defaultEffect;

    public PolicyDecisionEngine(CelBodyEvaluator bodyEvaluator, PolicyProperties policyProperties) {
        this.bodyEvaluator = bodyEvaluator;
        this.defaultEffect = policyProperties != null ? policyProperties.resolvedDefaultEffect() : RuleEffect.DENY;
    }

    public record Decision(RuleEffect effect, String reason, boolean unmatched) {
        public Decision(RuleEffect effect, String reason) {
            this(effect, reason, false);
        }

        public boolean isUnmatchedAction() {
            return unmatched;
        }
    }

    public record HeaderEvaluation(boolean needsBody, Decision decision) {}

    private record RequestMetadata(
            HttpMethod method,
            String host,
            Integer port,
            String path,
            Map<String, List<String>> queryParams,
            HttpHeaders headers) {

        static RequestMetadata from(HttpMethod method, URI url, HttpHeaders headers) {
            if (method == null || url == null || url.getHost() == null) {
                return null;
            }
            ProxyTarget target = ProxyTarget.fromUri(url);
            return new RequestMetadata(
                    method,
                    target.host(),
                    target.port(),
                    target.path(),
                    HttpPolicyValidator.parseQueryParams(target.rawQuery()),
                    headers);
        }

        static RequestMetadata from(HttpActionEntity action) {
            if (action == null) {
                return null;
            }
            return new RequestMetadata(
                    action.getMethod(),
                    action.getHost(),
                    action.getPort(),
                    action.getPath(),
                    action.getQueryParams(),
                    action.getHeaders());
        }
    }

    public HeaderEvaluation evaluateHeaders(
            HttpMethod method,
            URI url,
            HttpHeaders headers,
            List<HttpRuleEntity> rules) {
        RequestMetadata req = RequestMetadata.from(method, url, headers);
        if (req == null) {
            return new HeaderEvaluation(false, unmatchedDecision());
        }
        List<HttpRuleEntity> headerMatched = collectHeaderMatches(req, rules);
        boolean needsBody = false;
        for (HttpRuleEntity rule : headerMatched) {
            if (hasBodyCondition(rule)) {
                needsBody = true;
            } else if (rule.getEffect() == RuleEffect.DENY) {
                return new HeaderEvaluation(false, new Decision(RuleEffect.DENY, RULE_DENIED_REASON));
            }
        }
        if (needsBody) {
            return new HeaderEvaluation(true, null);
        }
        return new HeaderEvaluation(false, resolveDecision(headerMatched, rule -> true));
    }

    public Decision evaluateHttp(HttpActionEntity action, List<HttpRuleEntity> rules) {
        RequestMetadata req = RequestMetadata.from(action);
        if (req == null) {
            return unmatchedDecision();
        }
        List<HttpRuleEntity> headerMatched = collectHeaderMatches(req, rules);
        if (action.isBodyTruncated()) {
            return resolveDecision(headerMatched, rule -> !hasBodyCondition(rule));
        }
        return evaluateBodyRules(headerMatched, expressions -> bodyEvaluator.evaluateAll(expressions, action.getBody()));
    }

    public Decision evaluateWithBodyFile(
            HttpMethod method,
            URI url,
            HttpHeaders headers,
            Path bodyFile,
            List<HttpRuleEntity> rules) {
        RequestMetadata req = RequestMetadata.from(method, url, headers);
        if (req == null) {
            return unmatchedDecision();
        }
        List<HttpRuleEntity> headerMatched = collectHeaderMatches(req, rules);
        return evaluateBodyRules(headerMatched, expressions -> bodyEvaluator.evaluateAll(expressions, bodyFile));
    }

    private Decision evaluateBodyRules(
            List<HttpRuleEntity> headerMatched,
            Function<List<String>, CelBodyEvaluator.BatchResult> evaluator) {
        Set<String> expressions = new LinkedHashSet<>();
        for (HttpRuleEntity rule : headerMatched) {
            if (hasBodyCondition(rule)) {
                expressions.add(rule.getBodyCondition());
            } else if (rule.getEffect() == RuleEffect.DENY) {
                return new Decision(RuleEffect.DENY, RULE_DENIED_REASON);
            }
        }
        if (expressions.isEmpty()) {
            return resolveDecision(headerMatched, rule -> true);
        }
        CelBodyEvaluator.BatchResult batch = evaluator.apply(List.copyOf(expressions));
        if (!batch.validJson()) {
            return new Decision(RuleEffect.DENY, INVALID_BODY_REASON);
        }
        return resolveDecision(
                headerMatched,
                rule -> !hasBodyCondition(rule) || batch.matchedExpressions().contains(rule.getBodyCondition()));
    }

    private static List<HttpRuleEntity> collectHeaderMatches(RequestMetadata req, List<HttpRuleEntity> rules) {
        List<HttpRuleEntity> matched = new ArrayList<>();
        for (HttpRuleEntity rule : rules) {
            Objects.requireNonNull(rule.getEffect(), RuleEffect.ERROR_EFFECT_REQUIRED);
            if (matchesRequestMetadata(req, rule)) {
                matched.add(rule);
            }
        }
        return matched;
    }

    private Decision resolveDecision(List<HttpRuleEntity> matched, Predicate<HttpRuleEntity> include) {
        boolean hasAsk = false;
        boolean hasMatch = false;
        for (HttpRuleEntity rule : matched) {
            if (!include.test(rule)) {
                continue;
            }
            hasMatch = true;
            if (rule.getEffect() == RuleEffect.DENY) {
                return new Decision(RuleEffect.DENY, RULE_DENIED_REASON);
            }
            if (rule.getEffect() == RuleEffect.ASK) {
                hasAsk = true;
            }
        }
        if (hasAsk) {
            return new Decision(RuleEffect.ASK, RULE_REQUIRES_APPROVAL_REASON);
        }
        if (hasMatch) {
            return new Decision(RuleEffect.ALLOW, RULE_ALLOWED_REASON);
        }
        return unmatchedDecision();
    }

    private Decision unmatchedDecision() {
        String reason = switch (defaultEffect) {
            case ALLOW -> NO_MATCHING_RULE_ALLOW_REASON;
            case ASK -> NO_MATCHING_RULE_APPROVAL_REASON;
            case DENY -> NO_MATCHING_RULE_REASON;
        };
        return new Decision(defaultEffect, reason, true);
    }

    private static boolean hasBodyCondition(HttpRuleEntity rule) {
        return rule.getBodyCondition() != null && !rule.getBodyCondition().isBlank();
    }

    private static boolean matchesRequestMetadata(RequestMetadata req, HttpRuleEntity rule) {
        return matchesAny(rule.getMethods(), req.method())
                && matchesAny(rule.getHosts(), req.host())
                && matchesAny(rule.getPorts(), req.port())
                && (rule.getPaths() == null || rule.getPaths().isEmpty()
                        || rule.getPaths().stream().anyMatch(pattern -> matchesPath(pattern, req.path())))
                && matchesQueryParams(rule.getQueryParams(), req.queryParams(), rule.getEffect())
                && matchesRuleHeaders(rule.getHeaders(), req.headers(), rule.getEffect());
    }

    private static <T> boolean matchesAny(List<T> allowed, T actual) {
        return allowed == null || allowed.isEmpty() || allowed.contains(actual);
    }

    private static boolean matchesQueryParams(
            Map<String, List<String>> expected, Map<String, List<String>> actual, RuleEffect effect) {
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        return expected.entrySet().stream()
                .allMatch(e -> matchesValues(e.getValue(), actual.get(e.getKey()), effect));
    }

    private static boolean matchesRuleHeaders(
            HttpHeaders expected, HttpHeaders actual, RuleEffect effect) {
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        return expected.headerNames().stream()
                .allMatch(name -> matchesValues(expected.get(name), actual.get(name), effect));
    }

    private static boolean matchesValues(
            List<String> expectedValues, List<String> actualValues, RuleEffect effect) {
        if (actualValues == null) {
            return false;
        }
        if (expectedValues == null || expectedValues.isEmpty()) {
            return true;
        }
        if (effect == RuleEffect.ALLOW) {
            return !actualValues.isEmpty() && actualValues.stream().allMatch(expectedValues::contains);
        }
        return actualValues.stream().anyMatch(expectedValues::contains);
    }

    private static boolean matchesPath(String pattern, String path) {
        if (pattern.endsWith("/*")) {
            return path.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(path);
    }
}
