package ai.whitekey.service;

import ai.whitekey.api.dto.CreateHttpRuleRequest;
import ai.whitekey.api.dto.CreateRuleRequest;
import ai.whitekey.api.dto.ProtocolConstants;
import ai.whitekey.api.dto.UpdateHttpRuleRequest;
import ai.whitekey.entity.HttpRuleEntity;
import ai.whitekey.entity.RuleEffect;
import ai.whitekey.entity.RuleEntity;
import ai.whitekey.exception.ConflictException;
import ai.whitekey.exception.ResourceNotFoundException;
import ai.whitekey.policy.HttpPolicyValidator;
import ai.whitekey.repository.HttpRuleRepository;
import ai.whitekey.repository.RuleRepository;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Service
public class RuleService {
    private static final int MAX_RULE_NAME_LENGTH = 64;
    private static final int MAX_AUTO_NAME_BASE_LENGTH = 54;
    private static final Pattern RULE_NAME_PATTERN =
            Pattern.compile("[a-z0-9_]{1," + MAX_RULE_NAME_LENGTH + "}");

    private final RuleRepository repository;
    private final HttpRuleRepository httpRuleRepository;
    private final HttpPolicyValidator validator;
    private final AtomicReference<List<HttpRuleEntity>> ruleCache = new AtomicReference<>();

    public RuleService(
            RuleRepository repository,
            HttpRuleRepository httpRuleRepository,
            HttpPolicyValidator validator) {
        this.repository = repository;
        this.httpRuleRepository = httpRuleRepository;
        this.validator = validator;
    }

    @Transactional
    public RuleEntity createRule(CreateRuleRequest request) {
        if (request instanceof CreateHttpRuleRequest httpReq) {
            String validName = normalizeAndValidateName(httpReq.name());
            if (repository.existsByName(validName)) {
                throw new ConflictException("Rule already exists: " + validName);
            }
            return saveNewHttpRule(
                    validName,
                    RuleEffect.fromString(httpReq.effect()),
                    validator.methodNames(httpReq.methods()),
                    httpReq.hosts(),
                    httpReq.ports(),
                    httpReq.paths(),
                    httpReq.queryParams(),
                    validator.headers(httpReq.headers()),
                    httpReq.bodyCondition());
        }
        throw new IllegalArgumentException("Unsupported rule request type: " + request.getClass().getName());
    }

    @Transactional
    public HttpRuleEntity createAutoHttpRule(
            String baseName,
            RuleEffect effect,
            List<HttpMethod> methods,
            List<String> hosts,
            List<Integer> ports,
            List<String> paths,
            Map<String, List<String>> queryParams) {
        String uniqueName = nextAvailableName(baseName);
        return saveNewHttpRule(
                uniqueName, effect, methods, hosts, ports, paths, queryParams, HttpHeaders.EMPTY, null);
    }

    private HttpRuleEntity saveNewHttpRule(
            String validName,
            RuleEffect effect,
            List<HttpMethod> methods,
            List<String> hosts,
            List<Integer> ports,
            List<String> paths,
            Map<String, List<String>> queryParams,
            HttpHeaders headers,
            String bodyCondition) {
        if (effect == null) {
            throw new IllegalArgumentException(RuleEffect.ERROR_EFFECT_REQUIRED);
        }
        HttpRuleEntity rule =
                new HttpRuleEntity(
                        validName,
                        effect,
                        validator.methods(methods),
                        validator.hosts(hosts),
                        validator.ports(ports),
                        validator.pathPatterns(paths),
                        validator.queryParams(queryParams),
                        headers != null ? headers : HttpHeaders.EMPTY,
                        validator.bodyCondition(bodyCondition));
        HttpRuleEntity saved = httpRuleRepository.save(rule);
        invalidateCache();
        return saved;
    }

    @Transactional(readOnly = true)
    public RuleEntity getRule(String name) {
        String normalized = normalizeLookupName(name);
        return repository
                .findByName(normalized)
                .orElseThrow(() -> ResourceNotFoundException.forRule(name));
    }

    @Transactional(readOnly = true)
    public List<RuleEntity> getRules() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public List<RuleEntity> getRules(String type) {
        if (type == null || type.isBlank()) {
            return getRules();
        }
        if (ProtocolConstants.HTTP.equalsIgnoreCase(type.trim())) {
            return List.copyOf(getHttpRules());
        }
        throw new IllegalArgumentException("Unsupported rule type: " + type);
    }

    public void invalidateCache() {
        this.ruleCache.set(null);
    }

    @Transactional(readOnly = true)
    public List<HttpRuleEntity> getHttpRules() {
        List<HttpRuleEntity> rules = ruleCache.get();
        if (rules == null) {
            rules = List.copyOf(httpRuleRepository.findAll());
            ruleCache.set(rules);
        }
        return rules;
    }

    @Transactional
    public HttpRuleEntity updateHttpRule(String currentName, UpdateHttpRuleRequest request) {
        RuleEntity rule = getRule(currentName);
        if (!(rule instanceof HttpRuleEntity httpRule)) {
            throw new IllegalStateException("Unsupported rule type: " + rule.getClass().getName());
        }
        if (request.name() != null) {
            String validNewName = normalizeAndValidateName(request.name());
            if (!validNewName.equals(httpRule.getName())) {
                if (repository.existsByName(validNewName)) {
                    throw new ConflictException("Rule already exists: " + validNewName);
                }
                httpRule.setName(validNewName);
            }
        }
        if (request.effect() != null) {
            httpRule.setEffect(RuleEffect.fromString(request.effect()));
        }
        if (request.methods() != null) {
            httpRule.setMethods(validator.methodNames(request.methods()));
        }
        if (request.hosts() != null) {
            httpRule.setHosts(validator.hosts(request.hosts()));
        }
        if (request.ports() != null) {
            httpRule.setPorts(validator.ports(request.ports()));
        }
        if (request.paths() != null) {
            httpRule.setPaths(validator.pathPatterns(request.paths()));
        }
        if (request.queryParams() != null) {
            httpRule.setQueryParams(validator.queryParams(request.queryParams()));
        }
        if (request.headers() != null) {
            httpRule.setHeaders(validator.headers(request.headers()));
        }
        if (request.bodyCondition() != null) {
            httpRule.setBodyCondition(validator.bodyCondition(request.bodyCondition()));
        }
        HttpRuleEntity saved = httpRuleRepository.save(httpRule);
        invalidateCache();
        return saved;
    }

    @Transactional
    public void deleteRule(String name) {
        repository.delete(getRule(name));
        invalidateCache();
    }

    private String nextAvailableName(String rawBase) {
        String sanitized = (rawBase == null ? "" : rawBase.toLowerCase(Locale.ROOT))
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (sanitized.length() > MAX_AUTO_NAME_BASE_LENGTH) {
            sanitized = sanitized.substring(0, MAX_AUTO_NAME_BASE_LENGTH).replaceAll("_+$", "");
        }
        if (sanitized.isEmpty()) {
            sanitized = "allow_rule";
        }
        String candidate = sanitized;
        int suffix = 1;
        while (repository.existsByName(candidate)) {
            candidate = sanitized + "_" + suffix++;
        }
        return candidate;
    }

    private static String normalizeAndValidateName(String rawName) {
        String normalized = normalizeLookupName(rawName);
        if (!RULE_NAME_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Rule name must be 1-" + MAX_RULE_NAME_LENGTH
                            + " characters of lowercase letters, digits, or '_'");
        }
        return normalized;
    }

    private static String normalizeLookupName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("Rule name must not be blank");
        }
        return rawName.trim().toLowerCase(Locale.ROOT);
    }
}
