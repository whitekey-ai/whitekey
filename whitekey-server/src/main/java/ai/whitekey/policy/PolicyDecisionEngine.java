package ai.whitekey.policy;

import ai.whitekey.api.dto.ProtocolConstants;
import ai.whitekey.entity.HttpActionEntity;
import ai.whitekey.entity.HttpRuleEntity;
import ai.whitekey.entity.RuleEffect;
import ai.whitekey.proxy.ProxyTarget;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
public final class PolicyDecisionEngine {
    public static final String NO_MATCHING_RULE_REASON = "No applicable rule allows this action";
    public static final String RULE_DENIED_REASON = "A matching rule explicitly denies this action";
    public static final String RULE_REQUIRES_APPROVAL_REASON = "A matching rule requires human approval";
    public static final String RULE_ALLOWED_REASON = "Matching rules allow this action";

    private final CelBodyEvaluator bodyEvaluator;

    public PolicyDecisionEngine(CelBodyEvaluator bodyEvaluator) {
        this.bodyEvaluator = bodyEvaluator;
    }

    public record MatchedRule(UUID ruleId, RuleEffect effect) {}

    public record Decision(RuleEffect effect, String reason, List<MatchedRule> matchedRules) {
        public boolean isUnmatchedAction() {
            return matchedRules.isEmpty() && reason.equals(NO_MATCHING_RULE_REASON);
        }
    }

    public record HeaderEvaluation(
            boolean needsBody,
            Decision decision,
            List<HttpRuleEntity> candidateRules) {}

    public HeaderEvaluation evaluateHeaders(
            HttpMethod method,
            URI url,
            HttpHeaders headers,
            List<HttpRuleEntity> rules) {
        List<HttpRuleEntity> sorted = rules.stream()
                .sorted(Comparator.comparing(HttpRuleEntity::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        List<HttpRuleEntity> headerMatchedRules = new ArrayList<>();
        for (HttpRuleEntity rule : sorted) {
            if (rule.getEffect() == null) {
                return new HeaderEvaluation(
                        false,
                        new Decision(
                                RuleEffect.DENY,
                                "Invalid applicable rule " + rule.getId() + ": " + RuleEffect.ERROR_EFFECT_REQUIRED,
                                List.of()),
                        List.of());
            }
            if (matchesHeaders(method, url, headers, rule)) {
                headerMatchedRules.add(rule);
            }
        }

        if (headerMatchedRules.isEmpty()) {
            return new HeaderEvaluation(
                    false,
                    new Decision(RuleEffect.DENY, NO_MATCHING_RULE_REASON, List.of()),
                    List.of());
        }

        boolean hasUnconditionalDeny = headerMatchedRules.stream()
                .anyMatch(r -> r.getEffect() == RuleEffect.DENY && (r.getBodyCondition() == null || r.getBodyCondition().isBlank()));
        if (hasUnconditionalDeny) {
            List<MatchedRule> matched = headerMatchedRules.stream()
                    .filter(r -> r.getEffect() == RuleEffect.DENY && (r.getBodyCondition() == null || r.getBodyCondition().isBlank()))
                    .map(r -> new MatchedRule(r.getId(), r.getEffect()))
                    .toList();
            return new HeaderEvaluation(
                    false,
                    new Decision(RuleEffect.DENY, RULE_DENIED_REASON, matched),
                    List.of());
        }

        boolean anyHasBodyCondition = headerMatchedRules.stream()
                .anyMatch(r -> r.getBodyCondition() != null && !r.getBodyCondition().isBlank());

        if (anyHasBodyCondition) {
            return new HeaderEvaluation(true, null, headerMatchedRules);
        }

        List<MatchedRule> matched = headerMatchedRules.stream()
                .map(r -> new MatchedRule(r.getId(), r.getEffect()))
                .toList();

        return new HeaderEvaluation(false, resolveDecision(matched), headerMatchedRules);
    }

    public Decision evaluateHttp(HttpActionEntity action, List<HttpRuleEntity> rules) {
        List<MatchedRule> matched = new ArrayList<>();
        for (HttpRuleEntity rule :
                rules.stream().sorted(Comparator.comparing(HttpRuleEntity::getId, Comparator.nullsLast(Comparator.naturalOrder()))).toList()) {
            if (rule.getEffect() == null) {
                return new Decision(
                        RuleEffect.DENY,
                        "Invalid applicable rule " + rule.getId() + ": " + RuleEffect.ERROR_EFFECT_REQUIRED,
                        List.of());
            }
            if (matchesHttp(action, rule)) {
                matched.add(new MatchedRule(rule.getId(), rule.getEffect()));
            }
        }
        return resolveDecision(matched);
    }

    public Decision evaluateWithBodyFile(
            HttpMethod method,
            URI url,
            HttpHeaders headers,
            Path bodyFile,
            List<HttpRuleEntity> rules) {
        List<MatchedRule> matched = new ArrayList<>();
        List<HttpRuleEntity> sorted = rules.stream()
                .sorted(Comparator.comparing(HttpRuleEntity::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        for (HttpRuleEntity rule : sorted) {
            if (rule.getEffect() == null) {
                return new Decision(
                        RuleEffect.DENY,
                        "Invalid applicable rule " + rule.getId() + ": " + RuleEffect.ERROR_EFFECT_REQUIRED,
                        List.of());
            }
            if (matchesHeaders(method, url, headers, rule) && matchesBody(rule, bodyFile)) {
                matched.add(new MatchedRule(rule.getId(), rule.getEffect()));
            }
        }
        return resolveDecision(matched);
    }

    private Decision resolveDecision(List<MatchedRule> matched) {
        List<MatchedRule> immutable = List.copyOf(matched);
        if (matched.stream().anyMatch(rule -> rule.effect() == RuleEffect.DENY)) {
            return new Decision(
                    RuleEffect.DENY, RULE_DENIED_REASON, immutable);
        }
        if (matched.stream().anyMatch(rule -> rule.effect() == RuleEffect.REQUIRE_APPROVAL)) {
            return new Decision(
                    RuleEffect.REQUIRE_APPROVAL,
                    RULE_REQUIRES_APPROVAL_REASON,
                    immutable);
        }
        if (!matched.isEmpty()) {
            return new Decision(RuleEffect.ALLOW, RULE_ALLOWED_REASON, immutable);
        }
        return new Decision(RuleEffect.DENY, NO_MATCHING_RULE_REASON, immutable);
    }

    public boolean matchesBody(HttpRuleEntity rule, byte[] bodyBytes) {
        if (rule.getBodyCondition() == null || rule.getBodyCondition().isBlank()) {
            return true;
        }
        return bodyEvaluator.matches(rule.getBodyCondition(), bodyBytes);
    }

    public boolean matchesBody(HttpRuleEntity rule, Path bodyFile) {
        if (rule.getBodyCondition() == null || rule.getBodyCondition().isBlank()) {
            return true;
        }
        return bodyEvaluator.matches(rule.getBodyCondition(), bodyFile);
    }

    public boolean matchesHeaders(
            HttpMethod method,
            URI url,
            HttpHeaders requestHeaders,
            HttpRuleEntity rule) {
        if (method == null || url == null || url.getHost() == null) {
            return false;
        }
        String host = url.getHost().toLowerCase(Locale.ROOT);
        int port = url.getPort() != -1
                ? url.getPort()
                : (ProtocolConstants.HTTPS.equalsIgnoreCase(url.getScheme()) ? ProxyTarget.DEFAULT_HTTPS_PORT : ProxyTarget.DEFAULT_HTTP_PORT);
        String rawPath = url.getRawPath();
        String path = (rawPath == null || rawPath.isEmpty()) ? "/" : rawPath;

        Map<String, List<String>> queryParams = UriComponentsBuilder.fromUri(url).build().getQueryParams();
        return matchesRequestMetadata(method, host, port, path, queryParams, requestHeaders, rule);
    }

    public boolean matchesHttp(HttpActionEntity action, HttpRuleEntity rule) {
        if (action == null) {
            return false;
        }
        if (!matchesRequestMetadata(
                action.getMethod(),
                action.getHost(),
                action.getPort(),
                action.getPath(),
                action.getQueryParams(),
                action.getHeaders(),
                rule)) {
            return false;
        }
        return matchesBody(rule, action.getBody());
    }

    private static boolean matchesRequestMetadata(
            HttpMethod method,
            String host,
            Integer port,
            String path,
            Map<String, List<String>> queryParams,
            HttpHeaders headers,
            HttpRuleEntity rule) {
        if (rule.getMethods() != null && !rule.getMethods().isEmpty() && !rule.getMethods().contains(method)) {
            return false;
        }
        if (rule.getHosts() != null && !rule.getHosts().isEmpty() && !rule.getHosts().contains(host)) {
            return false;
        }
        if (rule.getPorts() != null && !rule.getPorts().isEmpty() && !rule.getPorts().contains(port)) {
            return false;
        }
        if (rule.getPaths() != null && !rule.getPaths().isEmpty()
                && rule.getPaths().stream().noneMatch(pattern -> matchesPath(pattern, path))) {
            return false;
        }
        if (!matchesQueryParams(rule.getQueryParams(), queryParams)) {
            return false;
        }
        return matchesRuleHeaders(rule.getHeaders(), headers);
    }

    private static boolean matchesQueryParams(Map<String, List<String>> expected, Map<String, List<String>> actual) {
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        for (Map.Entry<String, List<String>> expectedParam : expected.entrySet()) {
            List<String> actualValues = actual.get(expectedParam.getKey());
            if (actualValues == null) {
                return false;
            }
            List<String> expectedValues = expectedParam.getValue();
            if (expectedValues != null
                    && !expectedValues.isEmpty()
                    && actualValues.stream().noneMatch(expectedValues::contains)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesRuleHeaders(HttpHeaders expected, HttpHeaders actual) {
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        for (String expectedHeaderName : expected.headerNames()) {
            List<String> actualValues = actual.get(expectedHeaderName);
            if (actualValues == null) {
                return false;
            }
            List<String> expectedValues = expected.get(expectedHeaderName);
            if (expectedValues != null
                    && !expectedValues.isEmpty()
                    && actualValues.stream().noneMatch(expectedValues::contains)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesPath(String pattern, String path) {
        if (pattern.endsWith("/*")) {
            return path.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(path);
    }
}
