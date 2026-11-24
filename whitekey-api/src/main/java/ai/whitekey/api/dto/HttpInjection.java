package ai.whitekey.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HttpInjection(
        String host,
        String injectionTarget,
        String key,
        String format
) {}
