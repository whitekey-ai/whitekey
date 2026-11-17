package ai.whitekey.config;

import ai.whitekey.api.dto.RuleEffect;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.policy")
public record PolicyProperties(String defaultEffect) {

    public PolicyProperties {
        resolve(defaultEffect);
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
