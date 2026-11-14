package ai.whitekey.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateHttpRuleRequest(
        String name,
        String type,
        String effect,
        List<String> methods,
        List<String> hosts,
        List<Integer> ports,
        List<String> paths,
        Map<String, List<String>> queryParams,
        Map<String, List<String>> headers,
        String bodyCondition
) implements CreateRuleRequest {
    public CreateHttpRuleRequest {
        if (type == null || type.isBlank()) {
            type = ProtocolConstants.HTTP;
        }
    }

    public CreateHttpRuleRequest(
            String name,
            String type,
            String effect,
            List<String> methods,
            List<String> hosts,
            List<Integer> ports,
            List<String> paths,
            String bodyCondition) {
        this(name, type, effect, methods, hosts, ports, paths, null, null, bodyCondition);
    }
}
