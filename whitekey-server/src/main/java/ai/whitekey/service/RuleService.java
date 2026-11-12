package ai.whitekey.service;

import ai.whitekey.entity.RuleEntity;
import ai.whitekey.api.dto.ProtocolConstants;
import ai.whitekey.entity.HttpRuleEntity;
import ai.whitekey.entity.RuleEffect;
import ai.whitekey.exception.ResourceNotFoundException;
import ai.whitekey.policy.HttpPolicyValidator;
import ai.whitekey.repository.RuleRepository;
import ai.whitekey.repository.HttpRuleRepository;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class RuleService {
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
    public HttpRuleEntity createHttpRule(
            RuleEffect effect,
            String description,
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
        List<String> validHosts = validator.hosts(hosts);
        List<String> validPaths = validator.pathPatterns(paths);
        List<Integer> validPorts = validator.ports(ports);
        List<HttpMethod> validMethods = validator.methods(methods);
        String validBodyCondition = validator.bodyCondition(bodyCondition);
        HttpRuleEntity rule =
                new HttpRuleEntity(
                        effect,
                        description,
                        validMethods,
                        validHosts,
                        validPorts,
                        validPaths,
                        queryParams != null ? queryParams : Map.of(),
                        headers != null ? headers : HttpHeaders.EMPTY,
                        validBodyCondition);
        HttpRuleEntity saved = httpRuleRepository.save(rule);
        invalidateCache();
        return saved;
    }

    @Transactional
    public HttpRuleEntity createHttpRule(
            RuleEffect effect,
            List<HttpMethod> methods,
            List<String> hosts,
            List<Integer> ports,
            List<String> paths,
            Map<String, List<String>> queryParams) {
        return createHttpRule(effect, null, methods, hosts, ports, paths, queryParams, HttpHeaders.EMPTY, null);
    }

    @Transactional(readOnly = true)
    public RuleEntity getRule(UUID id) {
        return repository
                .findById(id)
                .orElseThrow(() -> ResourceNotFoundException.forRule(id));
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
            ruleCache.compareAndSet(null, rules);
            rules = ruleCache.get();
        }
        return rules;
    }

    @Transactional
    public HttpRuleEntity updateHttpRule(
            UUID id,
            RuleEffect effect,
            List<HttpMethod> methods,
            List<String> hosts,
            List<Integer> ports,
            List<String> paths,
            Map<String, List<String>> queryParams,
            HttpHeaders headers,
            String bodyCondition) {
        RuleEntity rule = getRule(id);
        if (rule instanceof HttpRuleEntity httpRule) {
            if (effect != null) {
                httpRule.setEffect(effect);
            }
            if (methods != null) {
                httpRule.setMethods(validator.methods(methods));
            }
            if (hosts != null) {
                httpRule.setHosts(validator.hosts(hosts));
            }
            if (ports != null) {
                httpRule.setPorts(validator.ports(ports));
            }
            if (paths != null) {
                httpRule.setPaths(validator.pathPatterns(paths));
            }
            if (queryParams != null) {
                httpRule.setQueryParams(validator.queryParams(queryParams));
            }
            if (headers != null) {
                httpRule.setHeaders(headers);
            }
            if (bodyCondition != null) {
                httpRule.setBodyCondition(validator.bodyCondition(bodyCondition));
            }
            HttpRuleEntity saved = httpRuleRepository.save(httpRule);
            invalidateCache();
            return saved;
        }
        throw new IllegalStateException("Unsupported rule type: " + rule.getClass().getName());
    }

    @Transactional
    public void deleteRule(UUID id) {
        repository.delete(getRule(id));
        invalidateCache();
    }
}
