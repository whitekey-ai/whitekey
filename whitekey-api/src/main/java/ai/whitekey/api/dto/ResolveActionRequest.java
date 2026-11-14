package ai.whitekey.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ResolveActionRequest(
        String decision,
        boolean saveRule
) {}
