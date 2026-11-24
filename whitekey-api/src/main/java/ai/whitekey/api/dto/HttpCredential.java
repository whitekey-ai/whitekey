package ai.whitekey.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HttpCredential(
        String id,
        String name,
        String type,
        List<HttpInjection> injections,
        String maskedSecret
) implements Credential {
    public HttpCredential {
        if (type == null || type.isBlank()) {
            type = ProtocolConstants.HTTP;
        }
        injections = injections != null ? List.copyOf(injections) : List.of();
    }

    public HttpCredential(
            String id,
            String name,
            List<HttpInjection> injections,
            String maskedSecret) {
        this(id, name, ProtocolConstants.HTTP, injections, maskedSecret);
    }
}
