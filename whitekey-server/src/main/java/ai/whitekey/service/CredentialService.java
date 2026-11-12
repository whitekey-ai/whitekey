package ai.whitekey.service;

import ai.whitekey.api.dto.CreateCredentialRequest;
import ai.whitekey.api.dto.CreateHttpCredentialRequest;
import ai.whitekey.api.dto.Credential;
import ai.whitekey.api.dto.CredentialPreset;
import ai.whitekey.api.dto.HttpCredential;
import ai.whitekey.entity.CredentialEntity;
import ai.whitekey.entity.HttpCredentialEntity;
import ai.whitekey.entity.HttpCredentialTargetType;
import ai.whitekey.exception.ResourceNotFoundException;
import ai.whitekey.repository.CredentialRepository;
import ai.whitekey.repository.HttpCredentialRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class CredentialService {

    private final CredentialRepository repository;
    private final HttpCredentialRepository httpCredentialRepository;
    private final EncryptionService encryptionService;
    private final CredentialPresetService presetService;
    private final AtomicReference<List<HttpCredentialEntity>> credentialCache = new AtomicReference<>();

    public CredentialService(
            CredentialRepository repository,
            HttpCredentialRepository httpCredentialRepository,
            EncryptionService encryptionService,
            CredentialPresetService presetService) {
        this.repository = repository;
        this.httpCredentialRepository = httpCredentialRepository;
        this.encryptionService = encryptionService;
        this.presetService = presetService;
    }

    @Transactional
    public Credential create(CreateCredentialRequest request) {
        if (request instanceof CreateHttpCredentialRequest httpReq) {
            return createHttpCredential(httpReq);
        }
        throw new IllegalArgumentException("Unsupported credential request type: " + request.getClass().getName());
    }

    private Credential createHttpCredential(CreateHttpCredentialRequest request) {
        String name = request.name().trim();
        String host = request.host();
        HttpCredentialTargetType targetType = request.targetType() != null && !request.targetType().isBlank()
                ? HttpCredentialTargetType.valueOf(request.targetType().trim().toUpperCase(Locale.ROOT))
                : null;
        String targetKey = request.targetKey();
        String formatTemplate = request.formatTemplate();
        String rawSecret = request.secret().trim();

        if (request.preset() != null && !request.preset().isBlank()) {
            CredentialPreset preset = presetService.findPreset(request.preset())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown credential preset: " + request.preset()));
            if (host == null || host.isBlank()) {
                host = preset.defaultHost();
            }
            if (targetType == null && preset.targetType() != null && !preset.targetType().isBlank()) {
                targetType = HttpCredentialTargetType.valueOf(preset.targetType().trim().toUpperCase(Locale.ROOT));
            }
            if (targetKey == null || targetKey.isBlank()) {
                targetKey = preset.targetKey();
            }
            if (formatTemplate == null || formatTemplate.isBlank()) {
                formatTemplate = preset.formatTemplate();
            }
        }

        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Target host is required");
        }
        if (targetType == null) {
            targetType = HttpCredentialTargetType.HEADER;
        }
        if (targetKey == null || targetKey.isBlank()) {
            targetKey = HttpHeaders.AUTHORIZATION;
        }
        if (formatTemplate == null || formatTemplate.isBlank()) {
            formatTemplate = "%s";
        }

        String encrypted = encryptionService.encrypt(rawSecret);

        Optional<CredentialEntity> existing = repository.findByName(name);
        HttpCredentialEntity entity;
        if (existing.isPresent()) {
            if (existing.get() instanceof HttpCredentialEntity httpEntity) {
                entity = httpEntity;
                entity.setHost(host.trim().toLowerCase(Locale.ROOT));
                entity.setTargetType(targetType);
                entity.setTargetKey(targetKey.trim());
                entity.setFormatTemplate(formatTemplate.trim());
                entity.setEncryptedSecret(encrypted);
            } else {
                throw new IllegalStateException("Existing credential " + name + " is not an HTTP credential");
            }
        } else {
            entity = new HttpCredentialEntity(
                    name,
                    host.trim().toLowerCase(Locale.ROOT),
                    targetType,
                    targetKey.trim(),
                    formatTemplate.trim(),
                    encrypted);
        }

        HttpCredentialEntity saved = httpCredentialRepository.save(entity);
        invalidateCache();
        return toDto(saved, rawSecret);
    }

    public void invalidateCache() {
        this.credentialCache.set(null);
    }

    @Transactional(readOnly = true)
    public List<HttpCredentialEntity> getActiveHttpCredentials() {
        List<HttpCredentialEntity> creds = credentialCache.get();
        if (creds == null) {
            creds = List.copyOf(httpCredentialRepository.findAll());
            credentialCache.compareAndSet(null, creds);
            creds = credentialCache.get();
        }
        return creds;
    }

    @Transactional(readOnly = true)
    public List<Credential> list() {
        return repository.findAll().stream()
                .map(this::toDto)
                .toList();
    }

    public static final String DEFAULT_MASKED_SECRET = "****";
    public static final int MIN_UNMASKED_LENGTH = 8;
    public static final int MAX_VISIBLE_SECRET_PART = 4;
    public static final String MASK_ELLIPSIS = "...";

    @Transactional(readOnly = true)
    public Credential getByName(String name) {
        CredentialEntity entity = repository.findByName(name)
                .orElseThrow(() -> ResourceNotFoundException.forCredential(name));
        return toDto(entity);
    }

    @Transactional
    public void deleteByName(String name) {
        CredentialEntity entity = repository.findByName(name)
                .orElseThrow(() -> ResourceNotFoundException.forCredential(name));
        repository.delete(entity);
        invalidateCache();
    }

    private Credential toDto(CredentialEntity entity) {
        String decrypted = null;
        try {
            decrypted = encryptionService.decrypt(entity.getEncryptedSecret());
        } catch (Exception ignored) {
        }
        return toDto(entity, decrypted);
    }

    private Credential toDto(CredentialEntity entity, String rawSecret) {
        if (entity instanceof HttpCredentialEntity httpCred) {
            return new HttpCredential(
                    httpCred.getId() != null ? httpCred.getId().toString() : null,
                    httpCred.getName(),
                    httpCred.getHost(),
                    httpCred.getTargetType() != null ? httpCred.getTargetType().name() : null,
                    httpCred.getTargetKey(),
                    httpCred.getFormatTemplate(),
                    maskSecret(rawSecret));
        }
        throw new IllegalStateException("Unsupported credential entity type: " + entity.getClass().getName());
    }

    public static String maskSecret(String rawSecret) {
        if (rawSecret == null || rawSecret.isBlank()) {
            return DEFAULT_MASKED_SECRET;
        }
        if (rawSecret.length() <= MIN_UNMASKED_LENGTH) {
            return DEFAULT_MASKED_SECRET;
        }
        int visiblePrefix = Math.min(MAX_VISIBLE_SECRET_PART, rawSecret.length() / 4);
        int visibleSuffix = Math.min(MAX_VISIBLE_SECRET_PART, rawSecret.length() / 4);
        return rawSecret.substring(0, visiblePrefix) + MASK_ELLIPSIS + rawSecret.substring(rawSecret.length() - visibleSuffix);
    }
}
