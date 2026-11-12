package ai.whitekey.controller;

import ai.whitekey.api.dto.CreateHttpRuleRequest;
import ai.whitekey.api.dto.CreateRuleRequest;
import ai.whitekey.api.dto.Rule;
import ai.whitekey.api.dto.UpdateHttpRuleRequest;
import ai.whitekey.entity.HttpRuleEntity;
import ai.whitekey.entity.RuleEffect;
import ai.whitekey.mapper.RuleDtoMapper;
import ai.whitekey.policy.HttpPolicyValidator;
import ai.whitekey.service.RuleService;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/rules")
public class RuleController {
    private final RuleService rules;
    private final HttpPolicyValidator validator;
    private final RuleDtoMapper ruleDtoMapper;

    public RuleController(RuleService rules, HttpPolicyValidator validator, RuleDtoMapper ruleDtoMapper) {
        this.rules = rules;
        this.validator = validator;
        this.ruleDtoMapper = ruleDtoMapper;
    }

    @GetMapping
    public List<Rule> list(@RequestParam(required = false) String type) {
        return rules.getRules(type).stream().map(ruleDtoMapper::toRuleDto).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Rule create(@Valid @RequestBody CreateRuleRequest request) {
        if (request instanceof CreateHttpRuleRequest httpReq) {
            RuleEffect effect = RuleEffect.fromString(httpReq.effect());
            List<HttpMethod> methods = httpReq.methods() != null
                    ? httpReq.methods().stream().map(m -> HttpMethod.valueOf(m.trim().toUpperCase(Locale.ROOT))).toList()
                    : List.of();
            HttpHeaders headers = httpReq.headers() != null ? validator.headers(httpReq.headers()) : HttpHeaders.EMPTY;
            HttpRuleEntity created = rules.createHttpRule(
                    effect,
                    httpReq.description(),
                    methods,
                    httpReq.hosts(),
                    httpReq.ports(),
                    httpReq.paths(),
                    httpReq.queryParams(),
                    headers,
                    httpReq.bodyCondition());
            return ruleDtoMapper.toRuleDto(created);
        }
        throw new IllegalArgumentException("Unsupported rule request type: " + request.getClass().getName());
    }

    @GetMapping("/{ruleId}")
    public Rule get(@PathVariable UUID ruleId) {
        return ruleDtoMapper.toRuleDto(rules.getRule(ruleId));
    }

    @PatchMapping("/{ruleId}")
    public Rule update(
            @PathVariable UUID ruleId, @Valid @RequestBody UpdateHttpRuleRequest request) {
        RuleEffect effect = request.effect() != null ? RuleEffect.fromString(request.effect()) : null;
        List<HttpMethod> methods = request.methods() != null
                ? request.methods().stream().map(m -> HttpMethod.valueOf(m.trim().toUpperCase(Locale.ROOT))).toList()
                : null;
        HttpHeaders headers = request.headers() != null ? validator.headers(request.headers()) : null;
        HttpRuleEntity updated = rules.updateHttpRule(
                ruleId,
                effect,
                methods,
                request.hosts(),
                request.ports(),
                request.paths(),
                request.queryParams(),
                headers,
                request.bodyCondition());
        return ruleDtoMapper.toRuleDto(updated);
    }

    @DeleteMapping("/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID ruleId) {
        rules.deleteRule(ruleId);
    }
}
