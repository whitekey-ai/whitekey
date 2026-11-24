package ai.whitekey.entity;

import ai.whitekey.api.dto.RuleEffect;
import ai.whitekey.converter.HttpHeadersConverter;
import ai.whitekey.converter.HttpMethodListConverter;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "http_rule")
public class HttpRuleEntity extends RuleEntity {

    @Convert(converter = HttpMethodListConverter.class)
    @Column(name = "methods", nullable = false, columnDefinition = "TEXT")
    private List<HttpMethod> methods = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "hosts", nullable = false, columnDefinition = "TEXT")
    private List<String> hosts = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ports", nullable = false, columnDefinition = "TEXT")
    private List<Integer> ports = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "paths", nullable = false, columnDefinition = "TEXT")
    private List<String> paths = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "query_params", nullable = false, columnDefinition = "TEXT")
    private Map<String, List<String>> queryParams = new LinkedHashMap<>();

    @Convert(converter = HttpHeadersConverter.class)
    @Column(name = "headers", nullable = false, columnDefinition = "TEXT")
    private HttpHeaders headers = HttpHeaders.EMPTY;

    @Column(name = "body_condition", columnDefinition = "TEXT")
    private String bodyCondition;

    protected HttpRuleEntity() {}

    public HttpRuleEntity(
            String name,
            RuleEffect effect,
            List<HttpMethod> methods,
            List<String> hosts,
            List<Integer> ports,
            List<String> paths,
            Map<String, List<String>> queryParams,
            HttpHeaders headers,
            String bodyCondition) {
        super(name, effect);
        this.methods = copyList(methods);
        this.hosts = copyList(hosts);
        this.ports = copyList(ports);
        this.paths = copyList(paths);
        this.queryParams = copyMap(queryParams);
        this.headers = readOnlyHeaders(headers);
        this.bodyCondition = normalizeCondition(bodyCondition);
    }

    public List<HttpMethod> getMethods() {
        return Collections.unmodifiableList(methods);
    }

    public void setMethods(List<HttpMethod> methods) {
        this.methods = copyList(methods);
    }

    public List<String> getHosts() {
        return Collections.unmodifiableList(hosts);
    }

    public void setHosts(List<String> hosts) {
        this.hosts = copyList(hosts);
    }

    public List<Integer> getPorts() {
        return Collections.unmodifiableList(ports);
    }

    public void setPorts(List<Integer> ports) {
        this.ports = copyList(ports);
    }

    public List<String> getPaths() {
        return Collections.unmodifiableList(paths);
    }

    public void setPaths(List<String> paths) {
        this.paths = copyList(paths);
    }

    public Map<String, List<String>> getQueryParams() {
        return Collections.unmodifiableMap(queryParams);
    }

    public void setQueryParams(Map<String, List<String>> queryParams) {
        this.queryParams = copyMap(queryParams);
    }

    public HttpHeaders getHeaders() {
        return headers;
    }

    public void setHeaders(HttpHeaders headers) {
        this.headers = readOnlyHeaders(headers);
    }

    public String getBodyCondition() {
        return bodyCondition;
    }

    public void setBodyCondition(String bodyCondition) {
        this.bodyCondition = normalizeCondition(bodyCondition);
    }

    private static <T> List<T> copyList(List<T> items) {
        return items != null ? new ArrayList<>(items) : new ArrayList<>();
    }

    private static Map<String, List<String>> copyMap(Map<String, List<String>> map) {
        return map != null ? new LinkedHashMap<>(map) : new LinkedHashMap<>();
    }

    private static HttpHeaders readOnlyHeaders(HttpHeaders headers) {
        return headers != null ? HttpHeaders.readOnlyHttpHeaders(headers) : HttpHeaders.EMPTY;
    }

    private static String normalizeCondition(String bodyCondition) {
        return (bodyCondition != null && !bodyCondition.isBlank()) ? bodyCondition.trim() : null;
    }
}
