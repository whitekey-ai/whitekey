package ai.whitekey.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

@Service
public class AdminTokenService {

    private static final Logger log = LoggerFactory.getLogger(AdminTokenService.class);
    public static final String BEARER_PREFIX = "Bearer ";
    public static final String DEFAULT_TOKEN_FILE_NAME = "whitekey_admin_token";
    public static final String DEFAULT_TOKEN_PREFIX = "whitekey_admin_token_";
    public static final int TOKEN_RANDOM_BYTES = 32;

    private final String configuredToken;
    private final String dataDir;
    private final String tokenFileName;
    private final String tokenPrefix;
    private String adminToken;

    public AdminTokenService(
            @Value("${app.security.admin-token:}") String configuredToken,
            @Value(SecretFileLoader.DEFAULT_DATA_DIR_EXPRESSION) String dataDir,
            @Value("${app.security.token-file-name:" + DEFAULT_TOKEN_FILE_NAME + "}") String tokenFileName,
            @Value("${app.security.token-prefix:" + DEFAULT_TOKEN_PREFIX + "}") String tokenPrefix) {
        this.configuredToken = configuredToken;
        this.dataDir = dataDir;
        this.tokenFileName = tokenFileName;
        this.tokenPrefix = tokenPrefix;
    }

    @PostConstruct
    public void init() {
        var loaded = SecretFileLoader.loadOrCreate(
                log,
                "WhiteKey Admin Token",
                configuredToken,
                Path.of(dataDir, tokenFileName),
                this::generateToken);
        this.adminToken = loaded.value();
        if (loaded.temporary()) {
            log.warn("Using temporary in-memory WhiteKey Admin Token: {}", this.adminToken);
        } else if (loaded.generated()) {
            log.info("================================================================================");
            log.info("WhiteKey Admin Token: {}", this.adminToken);
            log.info("================================================================================");
        }
    }

    private String generateToken() {
        byte[] randomBytes = new byte[TOKEN_RANDOM_BYTES];
        new SecureRandom().nextBytes(randomBytes);
        return tokenPrefix + HexFormat.of().formatHex(randomBytes);
    }

    public static String extractBearerToken(String authHeader) {
        if (authHeader == null || !authHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = authHeader.substring(BEARER_PREFIX.length()).strip();
        return token.isEmpty() ? null : token;
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
