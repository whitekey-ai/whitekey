package ai.whitekey.mapper;

import ai.whitekey.api.dto.HttpRule;
import ai.whitekey.api.dto.ProtocolConstants;
import ai.whitekey.api.dto.Rule;
import ai.whitekey.entity.HttpRuleEntity;
import ai.whitekey.entity.RuleEntity;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RuleDtoMapper {

    public Rule toRuleDto(RuleEntity rule) {
        if (rule == null) {
            return null;
        }
        if (rule instanceof HttpRuleEntity http) {
            Map<String, List<String>> headersMap = new LinkedHashMap<>();
            if (http.getHeaders() != null) {
                http.getHeaders().forEach((k, v) -> headersMap.put(k, List.copyOf(v)));
            }
            List<String> methodNames = http.getMethods() != null
                    ? http.getMethods().stream().map(HttpMethod::name).toList()
                    : List.of();
            return new HttpRule(
                    http.getName(),
                    ProtocolConstants.HTTP,
                    http.getEffect() != null ? http.getEffect().name() : null,
                    methodNames,
                    http.getHosts(),
                    http.getPorts(),
                    http.getPaths(),
                    http.getQueryParams(),
                    headersMap,
                    http.getBodyCondition());
        }
        throw new IllegalStateException("Unsupported rule type: " + rule.getClass().getName());
    }
}
