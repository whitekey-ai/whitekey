package ai.whitekey.api.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type",
        defaultImpl = CreateHttpCredentialRequest.class,
        visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = CreateHttpCredentialRequest.class, name = ProtocolConstants.HTTP)
})
public sealed interface CreateCredentialRequest permits CreateHttpCredentialRequest {
    String name();
    String type();
    String secret();
}
