package ai.whitekey.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CredentialPreset(
        String id,
        String displayName,
        String defaultHost,
        String injectionTarget,
        String key,
        String format,
        String description
) {}
