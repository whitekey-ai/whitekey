package ai.whitekey.api.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type",
        defaultImpl = CreateHttpRuleRequest.class,
        visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = CreateHttpRuleRequest.class, name = ProtocolConstants.HTTP)
})
public sealed interface CreateRuleRequest permits CreateHttpRuleRequest {
    String type();
    String effect();
    String description();
}
