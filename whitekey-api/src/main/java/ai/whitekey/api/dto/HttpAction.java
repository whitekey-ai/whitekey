package ai.whitekey.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HttpAction(
        String id,
        String type,
        String status,
        String method,
        String uri,
        String host,
        Integer port,
        String path,
        Map<String, List<String>> queryParams,
        Map<String, List<String>> headers,
        String body
) implements Action {
    public HttpAction {
        if (type == null || type.isBlank()) {
            type = ProtocolConstants.HTTP;
        }
    }
}
