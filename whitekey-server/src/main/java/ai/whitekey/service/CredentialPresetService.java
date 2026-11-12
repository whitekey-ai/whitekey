package ai.whitekey.service;

import ai.whitekey.api.dto.CredentialPreset;
import ai.whitekey.config.CredentialPresetProperties;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class CredentialPresetService {

    private final CredentialPresetProperties properties;

    public CredentialPresetService(CredentialPresetProperties properties) {
        this.properties = properties;
    }

    public List<CredentialPreset> getAllPresets() {
        return properties.presets().stream()
                .map(p -> new CredentialPreset(
                        p.id(),
                        p.displayName(),
                        p.defaultHost(),
                        p.targetType() != null ? p.targetType().name() : null,
                        p.targetKey(),
                        p.formatTemplate(),
                        p.description()))
                .toList();
    }

    public Optional<CredentialPreset> findPreset(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String needle = id.trim().toLowerCase(Locale.ROOT);
        return getAllPresets().stream()
                .filter(p -> p.id().toLowerCase(Locale.ROOT).equals(needle))
                .findFirst();
    }
}
