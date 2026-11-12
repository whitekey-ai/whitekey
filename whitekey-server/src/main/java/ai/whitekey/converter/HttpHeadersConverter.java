package ai.whitekey.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import org.springframework.http.HttpHeaders;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Converter
public class HttpHeadersConverter implements AttributeConverter<HttpHeaders, String> {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, List<String>>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(HttpHeaders value) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        if (value != null) {
            value.forEach((name, values) -> map.put(name, List.copyOf(values)));
        }
        return JSON.writeValueAsString(map);
    }

    @Override
    public HttpHeaders convertToEntityAttribute(String value) {
        HttpHeaders headers = new HttpHeaders();
        if (value != null && !value.isBlank()) {
            Map<String, List<String>> map = JSON.readValue(value, TYPE);
            map.forEach(headers::put);
        }
        return HttpHeaders.readOnlyHttpHeaders(headers);
    }
}
