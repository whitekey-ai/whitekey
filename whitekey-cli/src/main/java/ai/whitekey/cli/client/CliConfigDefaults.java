package ai.whitekey.cli.client;

import java.nio.file.Path;

public final class CliConfigDefaults {
    private CliConfigDefaults() {}

    public static final String DEFAULT_SERVER_URL = "http://127.0.0.1:1111";
    public static final String ENV_SERVER_URL = "WHITEKEY_SERVER_URL";
    public static final String ENV_ADMIN_TOKEN = "WHITEKEY_ADMIN_TOKEN";
    public static final String TOKEN_FILE_SUBPATH = ".local/share/whitekey/whitekey_admin_token";

    public static Path resolveDefaultTokenFile() {
        String home = System.getProperty("user.home");
        return home != null ? Path.of(home, ".local", "share", "whitekey", "whitekey_admin_token") : null;
    }
}
