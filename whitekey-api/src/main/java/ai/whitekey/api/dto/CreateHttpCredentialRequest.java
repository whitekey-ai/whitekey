package ai.whitekey.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateHttpCredentialRequest(
        String name,
        String type,
        String preset,
        String host,
        String injectionTarget,
        String key,
        String format,
        String secret
) implements CreateCredentialRequest {
    public CreateHttpCredentialRequest {
        if (type == null || type.isBlank()) {
            type = ProtocolConstants.HTTP;
        }
    }

    public CreateHttpCredentialRequest(
            String name,
            String preset,
            String host,
            String injectionTarget,
            String key,
            String format,
            String secret) {
        this(name, ProtocolConstants.HTTP, preset, host, injectionTarget, key, format, secret);
    }
}
