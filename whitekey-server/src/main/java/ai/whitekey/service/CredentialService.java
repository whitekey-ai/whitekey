package ai.whitekey.service;

import ai.whitekey.api.dto.CreateCredentialRequest;
import ai.whitekey.api.dto.CreateHttpCredentialRequest;
import ai.whitekey.api.dto.Credential;
import ai.whitekey.api.dto.CredentialPreset;
import ai.whitekey.api.dto.HttpCredential;
import ai.whitekey.config.CacheConfig;
import ai.whitekey.entity.CredentialEntity;
import ai.whitekey.entity.HttpInjectionEntity;
import ai.whitekey.entity.HttpInjectionTarget;
import ai.whitekey.exception.ResourceNotFoundException;
import ai.whitekey.policy.HttpPolicyValidator;
import ai.whitekey.repository.CredentialRepository;
import ai.whitekey.repository.HttpInjectionRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class CredentialService {

    public static final String DEFAULT_MASKED_SECRET = "****";
    public static final int MIN_UNMASKED_LENGTH = 8;
    public static final int MAX_VISIBLE_SECRET_PART = 4;
    public static final String MASK_ELLIPSIS = "...";

    private final CredentialRepository repository;
    private final HttpInjectionRepository httpInjectionRepository;
    private final EncryptionService encryptionService;
    private final CredentialPresetService presetService;

    public CredentialService(
            CredentialRepository repository,
            HttpInjectionRepository httpInjectionRepository,
            EncryptionService encryptionService,
            CredentialPresetService presetService) {
        this.repository = repository;
        this.httpInjectionRepository = httpInjectionRepository;
        this.encryptionService = encryptionService;
        this.presetService = presetService;
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.HTTP_INJECTIONS, allEntries = true)
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

        String rawHost = firstNonBlank(request.host(), preset != null ? preset.defaultHost() : null, null);
        if (rawHost == null) {
            throw new IllegalArgumentException("Target host is required");
        }
        String host = HttpPolicyValidator.normalizeHostIfValid(rawHost);
        String rawInjectionTarget = firstNonBlank(
                request.injectionTarget(),
                preset != null ? preset.injectionTarget() : null,
                HttpInjectionTarget.HEADER.name());
        HttpInjectionTarget injectionTarget;
        try {
            injectionTarget = HttpInjectionTarget.valueOf(rawInjectionTarget.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid injection target: " + rawInjectionTarget + " (expected HEADER, QUERY, or COOKIE)");
        }
        String key = firstNonBlank(request.key(), preset != null ? preset.key() : null, HttpHeaders.AUTHORIZATION);
        String format = firstNonBlank(
                request.format(),
                preset != null ? preset.format() : null,
                HttpInjectionEntity.DEFAULT_FORMAT);
        String name = requireNonBlank(request.name(), "Credential name is required");
        String rawSecret = requireNonBlank(request.secret(), "Credential secret is required");
        String encrypted = encryptionService.encrypt(rawSecret);

        CredentialEntity credential = repository.findByName(name)
                .map(existing -> {
                    existing.setEncryptedSecret(encrypted);
                    return existing;
                })
                .orElseGet(() -> new CredentialEntity(name, encrypted));
        CredentialEntity savedCredential = repository.save(credential);

        HttpInjectionEntity injection = httpInjectionRepository.findByCredentialId(savedCredential.getId())
                .map(existing -> {
                    existing.setHost(host);
                    existing.setInjectionTarget(injectionTarget);
                    existing.setKey(key);
                    existing.setFormat(format);
                    return existing;
                })
                .orElseGet(() -> new HttpInjectionEntity(savedCredential, host, injectionTarget, key, format));

        HttpInjectionEntity savedInjection = httpInjectionRepository.save(injection);
        return toDto(savedInjection, rawSecret);
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
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

    @Transactional(readOnly = true)
    @Cacheable(CacheConfig.HTTP_INJECTIONS)
    public List<HttpInjectionEntity> getActiveHttpInjections() {
        return List.copyOf(httpInjectionRepository.findAllWithCredential());
    }

    @Transactional(readOnly = true)
    public List<Credential> list() {
        return httpInjectionRepository.findAllWithCredential().stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public Credential getByName(String name) {
        HttpInjectionEntity injection = httpInjectionRepository.findByCredentialName(name)
                .orElseThrow(() -> ResourceNotFoundException.forCredential(name));
        return toDto(injection);
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.HTTP_INJECTIONS, allEntries = true)
    public void deleteByName(String name) {
        CredentialEntity entity = repository.findByName(name)
                .orElseThrow(() -> ResourceNotFoundException.forCredential(name));
        httpInjectionRepository.deleteByCredentialId(entity.getId());
        repository.delete(entity);
    }

    private Credential toDto(HttpInjectionEntity injection) {
        String decrypted = null;
        try {
            decrypted = encryptionService.decrypt(injection.getCredential().getEncryptedSecret());
        } catch (Exception ignored) {
        }
        return toDto(injection, decrypted);
    }

    private Credential toDto(HttpInjectionEntity injection, String rawSecret) {
        CredentialEntity credential = injection.getCredential();
        return new HttpCredential(
                credential.getId() != null ? credential.getId().toString() : null,
                credential.getName(),
                injection.getHost(),
                injection.getInjectionTarget() != null ? injection.getInjectionTarget().name() : null,
                injection.getKey(),
                injection.getFormat(),
                maskSecret(rawSecret));
    }

    public static String maskSecret(String rawSecret) {
        if (rawSecret == null || rawSecret.isBlank() || rawSecret.length() <= MIN_UNMASKED_LENGTH) {
            return DEFAULT_MASKED_SECRET;
        }
        int visible = Math.min(MAX_VISIBLE_SECRET_PART, rawSecret.length() / 4);
        return rawSecret.substring(0, visible) + MASK_ELLIPSIS + rawSecret.substring(rawSecret.length() - visible);
    }
}
