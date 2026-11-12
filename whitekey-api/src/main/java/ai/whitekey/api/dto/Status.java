package ai.whitekey.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Status(
        boolean proxyRunning,
        int proxyPort,
        int activeRulesCount,
        int pendingApprovalsCount
) {}
