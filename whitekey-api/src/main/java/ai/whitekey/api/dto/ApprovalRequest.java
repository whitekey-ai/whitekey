package ai.whitekey.api.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type",
        defaultImpl = HttpApprovalRequest.class,
        visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = HttpApprovalRequest.class, name = ProtocolConstants.HTTP)
})
public sealed interface ApprovalRequest permits HttpApprovalRequest {
    String id();
    String type();
    String status();
    String statusMessage();
    Action action();
}
