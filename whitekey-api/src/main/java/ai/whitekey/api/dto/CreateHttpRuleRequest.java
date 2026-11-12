package ai.whitekey.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateHttpRuleRequest(
        String type,
        String effect,
        String description,
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
            String type,
            String effect,
            String description,
            List<String> methods,
            List<String> hosts,
            List<Integer> ports,
            List<String> paths,
            String bodyCondition) {
        this(type, effect, description, methods, hosts, ports, paths, null, null, bodyCondition);
    }
}
