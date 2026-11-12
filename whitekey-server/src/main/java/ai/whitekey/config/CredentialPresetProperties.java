package ai.whitekey.config;

import ai.whitekey.entity.HttpCredentialTargetType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app.credentials")
public record CredentialPresetProperties(List<PresetDefinition> presets) {

    public CredentialPresetProperties {
        presets = presets != null ? presets : List.of();
    }

    public record PresetDefinition(
            String id,
            String displayName,
            String defaultHost,
            HttpCredentialTargetType targetType,
            String targetKey,
            String formatTemplate,
            String description
    ) {}
}
