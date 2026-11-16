package ai.whitekey.service;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.function.Supplier;

final class SecretFileLoader {

    static final String DEFAULT_DATA_DIR_EXPRESSION = "${app.database.directory:${user.home}/.local/share/whitekey}";
    private static final String POSIX_VIEW = "posix";
    private static final String OWNER_READ_WRITE_PERMISSIONS = "rw-------";

    record LoadedSecret(String value, boolean generated, boolean temporary) {}

    private SecretFileLoader() {}

    static LoadedSecret loadOrCreate(
            Logger log,
            String label,
            String configuredValue,
            Path filePath,
            Supplier<String> generator) {
        if (configuredValue != null && !configuredValue.isBlank()) {
            log.info("{} loaded from environment/configuration.", label);
            return new LoadedSecret(configuredValue.trim(), false, false);
        }

        String generated = null;
        try {
            if (Files.exists(filePath) && Files.size(filePath) > 0) {
                String loaded = Files.readString(filePath, StandardCharsets.UTF_8).trim();
                log.info("{} loaded from file: {}", label, filePath);
                return new LoadedSecret(loaded, false, false);
            }

            generated = generator.get();
            if (filePath.getParent() != null) {
                Files.createDirectories(filePath.getParent());
            }
            Files.writeString(filePath, generated, StandardCharsets.UTF_8);

            if (FileSystems.getDefault().supportedFileAttributeViews().contains(POSIX_VIEW)) {
                try {
                    Files.setPosixFilePermissions(
                            filePath, PosixFilePermissions.fromString(OWNER_READ_WRITE_PERMISSIONS));
                } catch (Exception ignored) {
                }
            }

            log.info("Generated new {} and saved to: {}", label, filePath);
            return new LoadedSecret(generated, true, false);
        } catch (IOException error) {
            log.error("Failed to load or save {} at {}", label, filePath, error);
            if (generated == null) {
                generated = generator.get();
            }
            return new LoadedSecret(generated, true, true);
        }
    }
}
