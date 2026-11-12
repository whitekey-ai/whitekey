package ai.whitekey.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import org.springframework.http.HttpMethod;

import java.util.Locale;

@Converter
public class HttpMethodConverter implements AttributeConverter<HttpMethod, String> {

    @Override
    public String convertToDatabaseColumn(HttpMethod attribute) {
        return attribute == null ? null : attribute.name().toUpperCase(Locale.ROOT);
    }

    @Override
    public HttpMethod convertToEntityAttribute(String dbData) {
        return (dbData == null || dbData.isBlank())
                ? null
                : HttpMethod.valueOf(dbData.trim().toUpperCase(Locale.ROOT));
    }
}
