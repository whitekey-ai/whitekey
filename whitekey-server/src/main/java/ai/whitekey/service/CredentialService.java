package ai.whitekey.service;

import ai.whitekey.api.dto.CreateCredentialRequest;
import ai.whitekey.api.dto.CreateHttpCredentialRequest;
import ai.whitekey.api.dto.Credential;
import ai.whitekey.api.dto.CredentialPreset;
import ai.whitekey.api.dto.HttpCredential;
import ai.whitekey.api.dto.HttpInjection;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class CredentialService {

    public static final String DEFAULT_MASKED_SECRET = "****";
    public static final int MIN_UNMASKED_LENGTH = 8;
    public static final int MAX_VISIBLE_SECRET_PART = 4;
    public static final String MASK_ELLIPSIS = "...";

    private record InjectionSpec(
            String host,
            HttpInjectionTarget injectionTarget,
            String key,
            String format) {}

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
        CredentialPreset preset = isNonBlank(request.preset())
                ? presetService.findPreset(request.preset())
                        .orElseThrow(() -> new IllegalArgumentException("Unknown credential preset: " + request.preset()))
                : null;

        List<InjectionSpec> specs = resolveInjectionSpecs(request, preset);
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

        List<HttpInjectionEntity> existingInjections = httpInjectionRepository.findAllByCredentialId(savedCredential.getId());
        if (!existingInjections.isEmpty()) {
            httpInjectionRepository.deleteAll(existingInjections);
            httpInjectionRepository.flush();
        }

        List<HttpInjectionEntity> savedInjections = httpInjectionRepository.saveAll(
                specs.stream()
                        .map(spec -> new HttpInjectionEntity(
                                savedCredential, spec.host(), spec.injectionTarget(), spec.key(), spec.format()))
                        .toList());
        return toDto(savedCredential, savedInjections, rawSecret);
    }

    private List<InjectionSpec> resolveInjectionSpecs(
            CreateHttpCredentialRequest request, CredentialPreset preset) {
        boolean hasOverrides = isNonBlank(request.host())
                || isNonBlank(request.injectionTarget())
                || isNonBlank(request.key())
                || isNonBlank(request.format());

        if (preset != null && !hasOverrides) {
            if (preset.injections().isEmpty()) {
                throw new IllegalArgumentException("Preset has no injection targets: " + preset.id());
            }
            return preset.injections().stream()
                    .map(inj -> resolveSingleSpec(inj.host(), inj.injectionTarget(), inj.key(), inj.format(), null))
                    .toList();
        }

        HttpInjection fallback = (preset != null && !preset.injections().isEmpty())
                ? preset.injections().getFirst()
                : null;
        return List.of(resolveSingleSpec(
                request.host(), request.injectionTarget(), request.key(), request.format(), fallback));
    }

    private InjectionSpec resolveSingleSpec(
            String hostArg,
            String targetArg,
            String keyArg,
            String formatArg,
            HttpInjection fallback) {
        String rawHost = firstNonBlank(hostArg, fallback != null ? fallback.host() : null, null);
        if (rawHost == null) {
            throw new IllegalArgumentException("Target host is required");
        }
        String host = HttpPolicyValidator.normalizeHostIfValid(rawHost);
        String rawInjectionTarget = firstNonBlank(
                targetArg,
                fallback != null ? fallback.injectionTarget() : null,
                HttpInjectionTarget.HEADER.name());
        HttpInjectionTarget injectionTarget;
        try {
            injectionTarget = HttpInjectionTarget.valueOf(rawInjectionTarget.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid injection target: " + rawInjectionTarget + " (expected HEADER, QUERY, or COOKIE)");
        }
        String key = firstNonBlank(keyArg, fallback != null ? fallback.key() : null, HttpHeaders.AUTHORIZATION);
        String format = firstNonBlank(
                formatArg,
                fallback != null ? fallback.format() : null,
                HttpInjectionEntity.DEFAULT_FORMAT);
        return new InjectionSpec(host, injectionTarget, key, format);
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String requireNonBlank(String value, String message) {
        if (!isNonBlank(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String firstNonBlank(String primary, String fallback, String defaultValue) {
        if (isNonBlank(primary)) {
            return primary.trim();
        }
        if (isNonBlank(fallback)) {
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
        Map<UUID, List<HttpInjectionEntity>> grouped = new LinkedHashMap<>();
        for (HttpInjectionEntity injection : httpInjectionRepository.findAllWithCredential()) {
            grouped.computeIfAbsent(injection.getCredential().getId(), id -> new ArrayList<>())
                    .add(injection);
        }
        return grouped.values().stream()
                .map(group -> toDto(group.getFirst().getCredential(), group))
                .toList();
    }

    @Transactional(readOnly = true)
    public Credential getByName(String name) {
        List<HttpInjectionEntity> injections = httpInjectionRepository.findAllByCredentialName(name);
        if (injections.isEmpty()) {
            throw ResourceNotFoundException.forCredential(name);
        }
        return toDto(injections.getFirst().getCredential(), injections);
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.HTTP_INJECTIONS, allEntries = true)
    public void deleteByName(String name) {
        CredentialEntity entity = repository.findByName(name)
                .orElseThrow(() -> ResourceNotFoundException.forCredential(name));
        httpInjectionRepository.deleteByCredentialId(entity.getId());
        repository.delete(entity);
    }

    private Credential toDto(CredentialEntity credential, List<HttpInjectionEntity> injections) {
        String decrypted = null;
        try {
            decrypted = encryptionService.decrypt(credential.getEncryptedSecret());
        } catch (Exception ignored) {
        }
        return toDto(credential, injections, decrypted);
    }

    private Credential toDto(
            CredentialEntity credential,
            List<HttpInjectionEntity> injections,
            String rawSecret) {
        List<HttpInjection> injectionDtos = injections.stream()
                .map(inj -> new HttpInjection(
                        inj.getHost(),
                        inj.getInjectionTarget() != null ? inj.getInjectionTarget().name() : null,
                        inj.getKey(),
                        inj.getFormat()))
                .toList();
        return new HttpCredential(
                credential.getId() != null ? credential.getId().toString() : null,
                credential.getName(),
                injectionDtos,
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
