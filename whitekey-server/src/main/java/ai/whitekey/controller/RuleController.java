package ai.whitekey.controller;

import ai.whitekey.api.dto.CreateRuleRequest;
import ai.whitekey.api.dto.Rule;
import ai.whitekey.api.dto.UpdateHttpRuleRequest;
import ai.whitekey.mapper.RuleDtoMapper;
import ai.whitekey.service.RuleService;

import jakarta.validation.Valid;

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

@RestController
@RequestMapping("/rules")
public class RuleController {
    private final RuleService rules;
    private final RuleDtoMapper ruleDtoMapper;

    public RuleController(RuleService rules, RuleDtoMapper ruleDtoMapper) {
        this.rules = rules;
        this.ruleDtoMapper = ruleDtoMapper;
    }

    @GetMapping
    public List<Rule> list(@RequestParam(required = false) String type) {
        return rules.getRules(type).stream().map(ruleDtoMapper::toRuleDto).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Rule create(@Valid @RequestBody CreateRuleRequest request) {
        return ruleDtoMapper.toRuleDto(rules.createRule(request));
    }

    @GetMapping("/{name}")
    public Rule get(@PathVariable String name) {
        return ruleDtoMapper.toRuleDto(rules.getRule(name));
    }

    @PatchMapping("/{name}")
    public Rule update(
            @PathVariable String name, @Valid @RequestBody UpdateHttpRuleRequest request) {
        return ruleDtoMapper.toRuleDto(rules.updateHttpRule(name, request));
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name) {
        rules.deleteRule(name);
    }
}
