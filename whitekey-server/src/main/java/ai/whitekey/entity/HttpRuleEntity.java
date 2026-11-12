package ai.whitekey.entity;

import ai.whitekey.api.dto.ProtocolConstants;
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
            RuleEffect effect,
            String description,
            List<HttpMethod> methods,
            List<String> hosts,
            List<Integer> ports,
            List<String> paths,
            Map<String, List<String>> queryParams,
            HttpHeaders headers,
            String bodyCondition) {
        super(effect, description);
        this.methods = methods != null ? new ArrayList<>(methods) : new ArrayList<>();
        this.hosts = hosts != null ? new ArrayList<>(hosts) : new ArrayList<>();
        this.ports = ports != null ? new ArrayList<>(ports) : new ArrayList<>();
        this.paths = paths != null ? new ArrayList<>(paths) : new ArrayList<>();
        this.queryParams = queryParams != null ? new LinkedHashMap<>(queryParams) : new LinkedHashMap<>();
        this.headers = headers != null ? HttpHeaders.readOnlyHttpHeaders(headers) : HttpHeaders.EMPTY;
        this.bodyCondition = (bodyCondition != null && !bodyCondition.isBlank()) ? bodyCondition.trim() : null;
    }

    public HttpRuleEntity(
            RuleEffect effect,
            List<HttpMethod> methods,
            List<String> hosts,
            List<Integer> ports,
            List<String> paths,
            Map<String, List<String>> queryParams,
            HttpHeaders headers,
            String bodyCondition) {
        this(effect, null, methods, hosts, ports, paths, queryParams, headers, bodyCondition);
    }

    public List<HttpMethod> getMethods() {
        return methods;
    }

    public void setMethods(List<HttpMethod> methods) {
        this.methods = methods != null ? new ArrayList<>(methods) : new ArrayList<>();
    }

    public List<String> getHosts() {
        return hosts;
    }

    public void setHosts(List<String> hosts) {
        this.hosts = hosts != null ? new ArrayList<>(hosts) : new ArrayList<>();
    }

    public List<Integer> getPorts() {
        return ports;
    }

    public void setPorts(List<Integer> ports) {
        this.ports = ports != null ? new ArrayList<>(ports) : new ArrayList<>();
    }

    public List<String> getPaths() {
        return paths;
    }

    public void setPaths(List<String> paths) {
        this.paths = paths != null ? new ArrayList<>(paths) : new ArrayList<>();
    }

    public Map<String, List<String>> getQueryParams() {
        return queryParams;
    }

    public void setQueryParams(Map<String, List<String>> queryParams) {
        this.queryParams = queryParams != null ? new LinkedHashMap<>(queryParams) : new LinkedHashMap<>();
    }

    public HttpHeaders getHeaders() {
        return headers;
    }

    public void setHeaders(HttpHeaders headers) {
        this.headers = headers != null ? HttpHeaders.readOnlyHttpHeaders(headers) : HttpHeaders.EMPTY;
    }

    public String getBodyCondition() {
        return bodyCondition;
    }

    public void setBodyCondition(String bodyCondition) {
        this.bodyCondition = (bodyCondition != null && !bodyCondition.isBlank()) ? bodyCondition.trim() : null;
    }

    @Override
    public String getType() {
        return ProtocolConstants.HTTP;
    }
}
