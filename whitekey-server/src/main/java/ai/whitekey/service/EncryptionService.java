package ai.whitekey.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class EncryptionService {

    private static final Logger log = LoggerFactory.getLogger(EncryptionService.class);
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    public static final int KEY_BYTE_LENGTH = 32;
    public static final String DIGEST_ALGORITHM = "SHA-256";
    public static final String DEFAULT_KEY_FILE_NAME = "whitekey_master.key";
    private static final Pattern HEX_64_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");

    private final String configuredKey;
    private final String dataDir;
    private final String keyFileName;
    private final SecureRandom secureRandom = new SecureRandom();
    private SecretKey secretKey;

    public EncryptionService(
            @Value("${app.security.encryption-key:}") String configuredKey,
            @Value(AdminTokenService.DEFAULT_DATA_DIR_EXPRESSION) String dataDir,
            @Value("${app.security.master-key-file-name:" + DEFAULT_KEY_FILE_NAME + "}") String keyFileName) {
        this.configuredKey = configuredKey;
        this.dataDir = dataDir;
        this.keyFileName = keyFileName;
    }

    @PostConstruct
    public void init() {
        if (configuredKey != null && !configuredKey.isBlank()) {
            this.secretKey = deriveKey(configuredKey.trim());
            log.info("Encryption master key loaded from environment/configuration.");
            return;
        }

        Path keyPath = Path.of(dataDir, keyFileName);
        try {
            if (Files.exists(keyPath) && Files.size(keyPath) > 0) {
                String hex = Files.readString(keyPath, StandardCharsets.UTF_8).trim();
                this.secretKey = deriveKey(hex);
                log.info("Encryption master key loaded from file: {}", keyPath);
                return;
            }

            this.secretKey = generateSecretKey();
            String hex = HexFormat.of().formatHex(this.secretKey.getEncoded());

            if (keyPath.getParent() != null) {
                Files.createDirectories(keyPath.getParent());
            }

            Files.writeString(keyPath, hex, StandardCharsets.UTF_8);

            if (FileSystems.getDefault().supportedFileAttributeViews().contains(AdminTokenService.POSIX_VIEW)) {
                Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(AdminTokenService.OWNER_READ_WRITE_PERMISSIONS);
                try {
                    Files.setPosixFilePermissions(keyPath, permissions);
                } catch (Exception ignored) {
                }
            }

            log.info("Generated new encryption master key and saved to: {}", keyPath);
        } catch (IOException error) {
            log.error("Failed to load or save encryption master key at {}", keyPath, error);
            if (this.secretKey == null) {
                this.secretKey = generateSecretKey();
            }
            log.warn("Using temporary in-memory encryption key. Credentials will not persist across restarts!");
        }
    }

    private SecretKey generateSecretKey() {
        byte[] keyBytes = new byte[KEY_BYTE_LENGTH];
        secureRandom.nextBytes(keyBytes);
        return new SecretKeySpec(keyBytes, ALGORITHM);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt secret", e);
        }
    }

    public String decrypt(String encryptedBase64) {
        if (encryptedBase64 == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedBase64);
            if (combined.length < GCM_IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("Corrupted encrypted data");
            }

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    secretKey,
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, combined, 0, GCM_IV_LENGTH_BYTES));

            byte[] decrypted =
                    cipher.doFinal(combined, GCM_IV_LENGTH_BYTES, combined.length - GCM_IV_LENGTH_BYTES);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to decrypt secret (master key mismatch?)", e);
        }
    }

    private SecretKey deriveKey(String input) {
        if (HEX_64_PATTERN.matcher(input).matches()) {
            byte[] keyBytes = HexFormat.of().parseHex(input);
            return new SecretKeySpec(keyBytes, ALGORITHM);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            byte[] keyBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, ALGORITHM);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(DIGEST_ALGORITHM + " algorithm missing", e);
        }
    }
}
