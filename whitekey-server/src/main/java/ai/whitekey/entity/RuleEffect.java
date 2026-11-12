package ai.whitekey.entity;

import java.util.Locale;

public enum RuleEffect {
    ALLOW,
    DENY,
    REQUIRE_APPROVAL;

    public static final String ERROR_EFFECT_REQUIRED = "Rule effect is required";

    public static RuleEffect fromString(String effect) {
        if (effect == null || effect.isBlank()) {
            throw new IllegalArgumentException(ERROR_EFFECT_REQUIRED);
        }
        return switch (effect.trim().toUpperCase(Locale.ROOT)) {
            case "ALLOW" -> ALLOW;
            case "BLOCK", "DENY" -> DENY;
            case "ASK", "REQUIRE_APPROVAL" -> REQUIRE_APPROVAL;
            default -> RuleEffect.valueOf(effect.trim().toUpperCase(Locale.ROOT));
        };
    }
}
