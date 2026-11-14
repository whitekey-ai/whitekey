package ai.whitekey.entity;

import ai.whitekey.api.dto.ProtocolConstants;
import ai.whitekey.converter.HttpHeadersConverter;
import ai.whitekey.converter.HttpMethodConverter;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Entity
@Table(
        name = "http_action",
        indexes = {@Index(name = "http_action_host", columnList = "host")})
public class HttpActionEntity extends ActionEntity {

    @Convert(converter = HttpMethodConverter.class)
    @Column(name = "method", nullable = false, columnDefinition = "TEXT")
    private HttpMethod method;

    @Column(name = "uri", nullable = false, columnDefinition = "TEXT")
    private String uri;

    @Column(name = "host", nullable = false, columnDefinition = "TEXT")
    private String host;

    @Column(name = "port", nullable = false)
    private int port;

    @Column(name = "path", nullable = false, columnDefinition = "TEXT")
    private String path;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "query_params", nullable = false, columnDefinition = "TEXT")
    private Map<String, List<String>> queryParams = Collections.emptyMap();

    @Convert(converter = HttpHeadersConverter.class)
    @Column(name = "headers", nullable = false, columnDefinition = "TEXT")
    private HttpHeaders headers = HttpHeaders.EMPTY;

    @Column(name = "body", nullable = false)
    private byte[] body = new byte[0];

    protected HttpActionEntity() {}

    public HttpActionEntity(
            HttpMethod method,
            String uri,
            String host,
            int port,
            String path,
            Map<String, List<String>> queryParams,
            HttpHeaders headers,
            byte[] body,
            ActionStatus status
    ) {
        super(status);
        this.method = method;
        this.uri = uri;
        this.host = host;
        this.port = port;
        this.path = path;
        this.queryParams = queryParams != null ? queryParams : Collections.emptyMap();
        this.headers = headers != null ? headers : HttpHeaders.EMPTY;
        this.body = body != null ? body : new byte[0];
    }

    public HttpMethod getMethod() {
        return method;
    }

    public void setMethod(HttpMethod method) {
        this.method = method;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Map<String, List<String>> getQueryParams() {
        return queryParams;
    }

    public void setQueryParams(Map<String, List<String>> queryParams) {
        this.queryParams = queryParams != null ? queryParams : Collections.emptyMap();
    }

    public HttpHeaders getHeaders() {
        return headers;
    }

    public void setHeaders(HttpHeaders headers) {
        this.headers = headers != null ? headers : HttpHeaders.EMPTY;
    }

    public byte[] getBody() {
        return body;
    }

    public void setBody(byte[] body) {
        this.body = body != null ? body : new byte[0];
    }

    @Override
    public String getType() {
        return ProtocolConstants.HTTP;
    }
}
