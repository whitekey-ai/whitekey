package ai.whitekey.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;

@Service
public class AdminTokenService {

    private static final Logger log = LoggerFactory.getLogger(AdminTokenService.class);
    public static final String DEFAULT_TOKEN_FILE_NAME = "whitekey_admin_token";
    public static final String DEFAULT_TOKEN_PREFIX = "whitekey_admin_token_";
    public static final int TOKEN_RANDOM_BYTES = 32;
    public static final String POSIX_VIEW = "posix";
    public static final String OWNER_READ_WRITE_PERMISSIONS = "rw-------";
    public static final String DEFAULT_DATA_DIR_EXPRESSION = "${app.database.directory:${user.home}/.local/share/whitekey}";

    private final String configuredToken;
    private final String dataDir;
    private final String tokenFileName;
    private final String tokenPrefix;
    private String adminToken;

    public AdminTokenService(
            @Value("${app.security.admin-token:}") String configuredToken,
            @Value(DEFAULT_DATA_DIR_EXPRESSION) String dataDir,
            @Value("${app.security.token-file-name:" + DEFAULT_TOKEN_FILE_NAME + "}") String tokenFileName,
            @Value("${app.security.token-prefix:" + DEFAULT_TOKEN_PREFIX + "}") String tokenPrefix) {
        this.configuredToken = configuredToken;
        this.dataDir = dataDir;
        this.tokenFileName = tokenFileName;
        this.tokenPrefix = tokenPrefix;
    }

    @PostConstruct
    public void init() {
        if (configuredToken != null && !configuredToken.isBlank()) {
            this.adminToken = configuredToken.trim();
            log.info("WhiteKey Admin Token loaded from environment/configuration.");
            return;
        }

        Path tokenPath = Path.of(dataDir, tokenFileName);
        try {
            if (Files.exists(tokenPath) && Files.size(tokenPath) > 0) {
                this.adminToken = Files.readString(tokenPath, StandardCharsets.UTF_8).trim();
                log.info("WhiteKey Admin Token loaded from file: {}", tokenPath);
                return;
            }

            byte[] randomBytes = new byte[TOKEN_RANDOM_BYTES];
            new SecureRandom().nextBytes(randomBytes);
            String token = tokenPrefix + HexFormat.of().formatHex(randomBytes);

            if (tokenPath.getParent() != null) {
                Files.createDirectories(tokenPath.getParent());
            }

            Files.writeString(tokenPath, token, StandardCharsets.UTF_8);

            if (FileSystems.getDefault().supportedFileAttributeViews().contains(POSIX_VIEW)) {
                Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(OWNER_READ_WRITE_PERMISSIONS);
                try {
                    Files.setPosixFilePermissions(tokenPath, permissions);
                } catch (Exception ignored) {
                }
            }

            this.adminToken = token;
            log.info("Generated new WhiteKey Admin Token and saved to: {}", tokenPath);
            log.info("================================================================================");
            log.info("WhiteKey Admin Token: {}", this.adminToken);
            log.info("================================================================================");
        } catch (IOException error) {
            log.error("Failed to load or save WhiteKey Admin Token file at {}", tokenPath, error);
            byte[] randomBytes = new byte[TOKEN_RANDOM_BYTES];
            new SecureRandom().nextBytes(randomBytes);
            this.adminToken = tokenPrefix + HexFormat.of().formatHex(randomBytes);
            log.warn("Using temporary in-memory WhiteKey Admin Token: {}", this.adminToken);
        }
    }

    public boolean isValidToken(String candidate) {
        if (candidate == null || candidate.isBlank() || this.adminToken == null) {
            return false;
        }
        byte[] expected = this.adminToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = candidate.trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
