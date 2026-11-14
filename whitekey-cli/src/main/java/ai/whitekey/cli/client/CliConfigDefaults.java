package ai.whitekey.cli.client;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CliConfigDefaults {
    private CliConfigDefaults() {}

    public static final String DEFAULT_SERVER_URL = "http://127.0.0.1:1111";
    public static final String ENV_SERVER_URL = "WHITEKEY_SERVER_URL";
    public static final String ENV_ADMIN_TOKEN = "WHITEKEY_ADMIN_TOKEN";
    public static final String TOKEN_FILE_SUBPATH = ".local/share/whitekey/whitekey_admin_token";

    public static Path resolveDefaultTokenFile() {
        String home = System.getProperty("user.home");
        return home != null ? Path.of(home, TOKEN_FILE_SUBPATH) : null;
    }

    public static File resolveConfigFile() {
        List<Path> candidates = new ArrayList<>();
        candidates.add(Path.of(".env"));
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            candidates.add(Path.of(userHome, ".config/whitekey/.env"));
            candidates.add(Path.of(userHome, ".local/share/whitekey/whitekey.env"));
        }
        return candidates.stream()
                .map(Path::toFile)
                .filter(f -> f.isFile() && f.canRead())
                .findFirst()
                .orElseGet(() -> Path.of(".env").toFile());
    }

    public static String resolveServerUrl(String explicitUrl) {
        return firstNonBlank(explicitUrl, System.getenv(ENV_SERVER_URL), DEFAULT_SERVER_URL);
    }

    public static String resolveAdminToken(String explicitToken) {
        String token = firstNonBlank(explicitToken, System.getenv(ENV_ADMIN_TOKEN));
        if (token != null) {
            return token;
        }
        Path tokenFile = resolveDefaultTokenFile();
        if (tokenFile != null && Files.exists(tokenFile)) {
            try {
                return firstNonBlank(Files.readString(tokenFile, StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    public static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
