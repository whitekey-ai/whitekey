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
import java.util.concurrent.atomic.AtomicReference;

@Service
public class CredentialService {

    public static final String DEFAULT_MASKED_SECRET = "****";
    public static final int MIN_UNMASKED_LENGTH = 8;
    public static final int MAX_VISIBLE_SECRET_PART = 4;
    public static final String MASK_ELLIPSIS = "...";

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
        CredentialPreset preset = (request.preset() != null && !request.preset().isBlank())
                ? presetService.findPreset(request.preset())
                        .orElseThrow(() -> new IllegalArgumentException("Unknown credential preset: " + request.preset()))
                : null;

        String host = firstNonBlank(request.host(), preset != null ? preset.defaultHost() : null, null);
        if (host == null) {
            throw new IllegalArgumentException("Target host is required");
        }
        String rawType = firstNonBlank(
                request.targetType(),
                preset != null ? preset.targetType() : null,
                HttpCredentialTargetType.HEADER.name());
        HttpCredentialTargetType targetType = HttpCredentialTargetType.valueOf(rawType.toUpperCase(Locale.ROOT));
        String targetKey = firstNonBlank(request.targetKey(), preset != null ? preset.targetKey() : null, HttpHeaders.AUTHORIZATION);
        String formatTemplate = firstNonBlank(request.formatTemplate(), preset != null ? preset.formatTemplate() : null, "%s");
        String rawSecret = request.secret().trim();
        String encrypted = encryptionService.encrypt(rawSecret);
        String name = request.name().trim();

        HttpCredentialEntity entity = repository.findByName(name)
                .map(existing -> {
                    if (existing instanceof HttpCredentialEntity httpEntity) {
                        httpEntity.setHost(host);
                        httpEntity.setTargetType(targetType);
                        httpEntity.setTargetKey(targetKey);
                        httpEntity.setFormatTemplate(formatTemplate);
                        httpEntity.setEncryptedSecret(encrypted);
                        return httpEntity;
                    }
                    throw new IllegalStateException("Existing credential " + name + " is not an HTTP credential");
                })
                .orElseGet(() -> new HttpCredentialEntity(name, host, targetType, targetKey, formatTemplate, encrypted));

        HttpCredentialEntity saved = httpCredentialRepository.save(entity);
        invalidateCache();
        return toDto(saved, rawSecret);
    }

    private static String firstNonBlank(String primary, String fallback, String defaultValue) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        return defaultValue;
    }

    public void invalidateCache() {
        this.credentialCache.set(null);
    }

    @Transactional(readOnly = true)
    public List<HttpCredentialEntity> getActiveHttpCredentials() {
        List<HttpCredentialEntity> creds = credentialCache.get();
        if (creds == null) {
            creds = List.copyOf(httpCredentialRepository.findAll());
            credentialCache.set(creds);
        }
        return creds;
    }

    @Transactional(readOnly = true)
    public List<Credential> list() {
        return repository.findAll().stream()
                .map(this::toDto)
                .toList();
    }

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
        if (rawSecret == null || rawSecret.isBlank() || rawSecret.length() <= MIN_UNMASKED_LENGTH) {
            return DEFAULT_MASKED_SECRET;
        }
        int visible = Math.min(MAX_VISIBLE_SECRET_PART, rawSecret.length() / 4);
        return rawSecret.substring(0, visible) + MASK_ELLIPSIS + rawSecret.substring(rawSecret.length() - visible);
    }
}
