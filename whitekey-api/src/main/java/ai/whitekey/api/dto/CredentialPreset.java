package ai.whitekey.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CredentialPreset(
        String id,
        String displayName,
        String defaultHost,
        String targetType,
        String targetKey,
        String formatTemplate,
        String description
) {}
