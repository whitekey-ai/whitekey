package ai.whitekey.api.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type",
        defaultImpl = HttpCredential.class,
        visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = HttpCredential.class, name = ProtocolConstants.HTTP)
})
public sealed interface Credential permits HttpCredential {
    String id();
    String name();
    String type();
    String maskedSecret();
}
