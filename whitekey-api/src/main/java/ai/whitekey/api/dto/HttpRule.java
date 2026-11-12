package ai.whitekey.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HttpRule(
        String id,
        String type,
        String effect,
        List<String> methods,
        List<String> hosts,
        List<Integer> ports,
        List<String> paths,
        Map<String, List<String>> queryParams,
        Map<String, List<String>> headers,
        String bodyCondition,
        String description
) implements Rule {
    public HttpRule {
        if (type == null || type.isBlank()) {
            type = ProtocolConstants.HTTP;
        }
    }
}
