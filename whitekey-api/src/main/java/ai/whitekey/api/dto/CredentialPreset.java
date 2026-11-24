package ai.whitekey.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CredentialPreset(
        String id,
        String displayName,
        List<HttpInjection> injections,
        String description
) {
    public CredentialPreset {
        injections = injections != null ? List.copyOf(injections) : List.of();
    }
}
