package ai.whitekey.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HttpApprovalRequest(
        String id,
        String type,
        String status,
        String statusMessage,
        HttpAction action
) implements ApprovalRequest {
    public HttpApprovalRequest {
        if (type == null || type.isBlank()) {
            type = ProtocolConstants.HTTP;
        }
    }
}
