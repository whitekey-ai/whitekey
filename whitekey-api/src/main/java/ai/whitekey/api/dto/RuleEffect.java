package ai.whitekey.api.dto;

import java.util.Locale;

/**
 * Policy decision and rule effect shared across API contracts, server, and CLI.
 */
public enum RuleEffect {
    ALLOW,
    DENY,
    ASK;

    public static final String ERROR_EFFECT_REQUIRED = "Rule effect is required";

    public static RuleEffect fromString(String effect) {
        if (effect == null || effect.isBlank()) {
            throw new IllegalArgumentException(ERROR_EFFECT_REQUIRED);
        }
        return switch (effect.trim().toUpperCase(Locale.ROOT)) {
            case "ALLOW" -> ALLOW;
            case "DENY" -> DENY;
            case "ASK" -> ASK;
            default -> throw new IllegalArgumentException(
                    "Invalid rule effect: " + effect + " (expected ALLOW, DENY, or ASK)");
        };
    }
}
