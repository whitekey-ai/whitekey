package ai.whitekey.config;

import ai.whitekey.api.dto.RuleEffect;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.policy")
public record PolicyProperties(String defaultEffect, Integer celCacheSize) {

    public static final int DEFAULT_CEL_CACHE_SIZE = 1000;

    public PolicyProperties {
        resolve(defaultEffect);
        celCacheSize = celCacheSize != null ? celCacheSize : DEFAULT_CEL_CACHE_SIZE;
    }

    public RuleEffect resolvedDefaultEffect() {
        return resolve(defaultEffect);
    }

    private static RuleEffect resolve(String value) {
        return (value == null || value.isBlank())
                ? RuleEffect.DENY
                : RuleEffect.fromString(value);
    }
}
