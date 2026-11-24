package ai.whitekey.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HttpCredential(
        String id,
        String name,
        String type,
        String host,
        String injectionTarget,
        String key,
        String format,
        String maskedSecret
) implements Credential {
    public HttpCredential {
        if (type == null || type.isBlank()) {
            type = ProtocolConstants.HTTP;
        }
    }

    public HttpCredential(
            String id,
            String name,
            String host,
            String injectionTarget,
            String key,
            String format,
            String maskedSecret) {
        this(id, name, ProtocolConstants.HTTP, host, injectionTarget, key, format, maskedSecret);
    }
}
