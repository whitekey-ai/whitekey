package ai.whitekey.entity;

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
        indexes = {@Index(name = "idx_http_action_host", columnList = "host")})
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

    @Column(name = "body_truncated", nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT 0")
    private boolean bodyTruncated = false;

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
            boolean bodyTruncated,
            ActionStatus status
    ) {
        super(status);
        this.method = method;
        this.uri = uri;
        this.host = host;
        this.port = port;
        this.path = path;
        this.queryParams = queryParams != null ? queryParams : Collections.emptyMap();
        this.headers = headers != null ? HttpHeaders.readOnlyHttpHeaders(headers) : HttpHeaders.EMPTY;
        this.body = body != null ? body : new byte[0];
        this.bodyTruncated = bodyTruncated;
    }

    public HttpMethod getMethod() {
        return method;
    }

    public String getUri() {
        return uri;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getPath() {
        return path;
    }

    public Map<String, List<String>> getQueryParams() {
        return Collections.unmodifiableMap(queryParams);
    }

    public HttpHeaders getHeaders() {
        return headers;
    }

    public byte[] getBody() {
        return body;
    }

    public boolean isBodyTruncated() {
        return bodyTruncated;
    }
}
