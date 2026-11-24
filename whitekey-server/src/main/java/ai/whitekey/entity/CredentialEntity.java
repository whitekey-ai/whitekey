package ai.whitekey.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

@Entity
@Table(
        name = "credential",
        indexes = {
                @Index(name = "idx_credential_name", columnList = "name", unique = true)
        })
public class CredentialEntity {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "encrypted_secret", nullable = false, columnDefinition = "TEXT")
    private String encryptedSecret;

    protected CredentialEntity() {}

    public CredentialEntity(String name, String encryptedSecret) {
        this.name = name != null ? name.trim() : null;
        this.encryptedSecret = encryptedSecret;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEncryptedSecret() {
        return encryptedSecret;
    }

    public void setEncryptedSecret(String encryptedSecret) {
        this.encryptedSecret = encryptedSecret;
    }
}
