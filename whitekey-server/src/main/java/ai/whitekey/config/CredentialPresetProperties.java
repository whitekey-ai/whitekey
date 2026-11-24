package ai.whitekey.config;

import ai.whitekey.entity.HttpInjectionTarget;
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
            List<InjectionDefinition> injections,
            String description
    ) {
        public PresetDefinition {
            injections = injections != null ? List.copyOf(injections) : List.of();
        }
    }

    public record InjectionDefinition(
            String host,
            HttpInjectionTarget injectionTarget,
            String key,
            String format
    ) {}
}
