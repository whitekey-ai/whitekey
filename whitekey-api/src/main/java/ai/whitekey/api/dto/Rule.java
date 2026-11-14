package ai.whitekey.api.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type",
        visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = HttpRule.class, name = ProtocolConstants.HTTP)
})
public sealed interface Rule permits HttpRule {
    String name();
    String type();
    String effect();
}
