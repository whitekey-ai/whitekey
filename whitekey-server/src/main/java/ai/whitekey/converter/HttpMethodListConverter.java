package ai.whitekey.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import org.springframework.http.HttpMethod;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Converter
public class HttpMethodListConverter implements AttributeConverter<List<HttpMethod>, String> {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<HttpMethod> attribute) {
        if (attribute == null) {
            return null;
        }
        List<String> names =
                attribute.stream().map(m -> m.name().toUpperCase(Locale.ROOT)).toList();
        return JSON.writeValueAsString(names);
    }

    @Override
    public List<HttpMethod> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        List<String> names = JSON.readValue(dbData, TYPE);
        return names.stream()
                .map(n -> HttpMethod.valueOf(n.trim().toUpperCase(Locale.ROOT)))
                .toList();
    }
}
