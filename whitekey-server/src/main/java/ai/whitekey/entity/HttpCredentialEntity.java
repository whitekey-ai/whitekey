package ai.whitekey.entity;

import ai.whitekey.api.dto.ProtocolConstants;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.util.Locale;

@Entity
@Table(
        name = "http_credential",
        indexes = {
                @Index(name = "idx_http_credential_host", columnList = "host")
        })
public class HttpCredentialEntity extends CredentialEntity {

    @Column(name = "host", nullable = false)
    private String host;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false)
    private HttpCredentialTargetType targetType;

    @Column(name = "target_key", nullable = false)
    private String targetKey;

    @Column(name = "format_template", nullable = false)
    private String formatTemplate = "%s";

    protected HttpCredentialEntity() {}

    public HttpCredentialEntity(
            String name,
            String host,
            HttpCredentialTargetType targetType,
            String targetKey,
            String formatTemplate,
            String encryptedSecret) {
        super(name, encryptedSecret);
        this.host = host != null ? host.trim().toLowerCase(Locale.ROOT) : null;
        this.targetType = targetType != null ? targetType : HttpCredentialTargetType.HEADER;
        this.targetKey = targetKey != null ? targetKey.trim() : null;
        this.formatTemplate = (formatTemplate != null && !formatTemplate.isBlank()) ? formatTemplate.trim() : "%s";
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host != null ? host.trim().toLowerCase(Locale.ROOT) : null;
    }

    public HttpCredentialTargetType getTargetType() {
        return targetType;
    }

    public void setTargetType(HttpCredentialTargetType targetType) {
        this.targetType = targetType;
    }

    public String getTargetKey() {
        return targetKey;
    }

    public void setTargetKey(String targetKey) {
        this.targetKey = targetKey != null ? targetKey.trim() : null;
    }

    public String getFormatTemplate() {
        return formatTemplate;
    }

    public void setFormatTemplate(String formatTemplate) {
        this.formatTemplate = (formatTemplate != null && !formatTemplate.isBlank()) ? formatTemplate.trim() : "%s";
    }

    @Override
    public String getType() {
        return ProtocolConstants.HTTP;
    }
}
