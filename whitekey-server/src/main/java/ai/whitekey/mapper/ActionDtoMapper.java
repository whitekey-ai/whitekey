package ai.whitekey.mapper;

import ai.whitekey.api.dto.Action;
import ai.whitekey.api.dto.HttpAction;
import ai.whitekey.api.dto.ProtocolConstants;
import ai.whitekey.entity.ActionEntity;
import ai.whitekey.entity.HttpActionEntity;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class ActionDtoMapper {

    public static final String BINARY_CONTENT_FORMAT = "[binary content, %d bytes]";
    private static final String TYPE_TEXT = "text";
    private static final Set<String> TEXT_SUBTYPES =
            Set.of("json", "xml", "yaml", "x-www-form-urlencoded", "javascript", "graphql");

    public Action toActionDto(ActionEntity actionEntity) {
        if (actionEntity == null) {
            return null;
        }
        if (actionEntity instanceof HttpActionEntity httpActionEntity) {
            return toHttpActionDto(httpActionEntity);
        }
        throw new IllegalStateException("Unsupported action entity type: " + actionEntity.getClass().getName());
    }

    public HttpAction toHttpActionDto(HttpActionEntity entity) {
        if (entity == null) {
            return null;
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        if (entity.getHeaders() != null) {
            entity.getHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
        }
        String bodyString = null;
        if (entity.getBody() != null && entity.getBody().length > 0) {
            bodyString = isTextPayload(entity)
                    ? new String(entity.getBody(), StandardCharsets.UTF_8)
                    : String.format(Locale.ROOT, BINARY_CONTENT_FORMAT, entity.getBody().length);
        }

        return new HttpAction(
            entity.getId() != null ? entity.getId().toString() : null,
            ProtocolConstants.HTTP,
            entity.getStatus() != null ? entity.getStatus().name() : null,
            entity.getMethod() != null ? entity.getMethod().name() : null,
            entity.getUri(),
            entity.getHost(),
            entity.getPort(),
            entity.getPath(),
            entity.getQueryParams(),
            headers,
            bodyString);
    }

    private boolean isTextPayload(HttpActionEntity entity) {
        if (entity.getHeaders() != null) {
            try {
                MediaType contentType = entity.getHeaders().getContentType();
                if (contentType != null) {
                    String type = contentType.getType();
                    String subtype = contentType.getSubtype().toLowerCase(Locale.ROOT);
                    return TYPE_TEXT.equalsIgnoreCase(type)
                            || TEXT_SUBTYPES.stream().anyMatch(subtype::contains);
                }
            } catch (Exception ignored) {
            }
        }
        return isUtf8(entity.getBody());
    }

    private static boolean isUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
