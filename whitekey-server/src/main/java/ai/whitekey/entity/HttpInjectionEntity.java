package ai.whitekey.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.UuidGenerator;

import java.util.Locale;
import java.util.UUID;

@Entity
@Table(
        name = "http_injection",
        indexes = {
                @Index(name = "idx_http_injection_host", columnList = "host"),
                @Index(name = "idx_http_injection_credential_id", columnList = "credential_id")
        })
public class HttpInjectionEntity {

    public static final String DEFAULT_FORMAT = "%s";

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "credential_id", nullable = false)
    private CredentialEntity credential;

    @Column(name = "host", nullable = false)
    private String host;

    @Enumerated(EnumType.STRING)
    @Column(name = "injection_target", nullable = false)
    private HttpInjectionTarget injectionTarget;

    @Column(name = "key", nullable = false)
    private String key;

    @Column(name = "format", nullable = false)
    private String format = DEFAULT_FORMAT;

    protected HttpInjectionEntity() {}

    public HttpInjectionEntity(
            CredentialEntity credential,
            String host,
            HttpInjectionTarget injectionTarget,
            String key,
            String format) {
        this.credential = credential;
        this.host = normalizeHost(host);
        this.injectionTarget = normalizeInjectionTarget(injectionTarget);
        this.key = normalizeKey(key);
        this.format = normalizeFormat(format);
    }

    public UUID getId() {
        return id;
    }

    public CredentialEntity getCredential() {
        return credential;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = normalizeHost(host);
    }

    public HttpInjectionTarget getInjectionTarget() {
        return injectionTarget;
    }

    public void setInjectionTarget(HttpInjectionTarget injectionTarget) {
        this.injectionTarget = normalizeInjectionTarget(injectionTarget);
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = normalizeKey(key);
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = normalizeFormat(format);
    }

    private static String normalizeHost(String host) {
        return host != null ? host.trim().toLowerCase(Locale.ROOT) : null;
    }

    private static HttpInjectionTarget normalizeInjectionTarget(HttpInjectionTarget injectionTarget) {
        return injectionTarget != null ? injectionTarget : HttpInjectionTarget.HEADER;
    }

    private static String normalizeKey(String key) {
        return key != null ? key.trim() : null;
    }

    private static String normalizeFormat(String format) {
        return (format != null && !format.isBlank())
                ? format.trim()
                : DEFAULT_FORMAT;
    }
}
