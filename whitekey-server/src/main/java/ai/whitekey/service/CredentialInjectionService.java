package ai.whitekey.service;

import ai.whitekey.entity.HttpInjectionEntity;
import ai.whitekey.policy.HttpPolicyValidator;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CredentialInjectionService {

    public static final String WILDCARD_HOST_PREFIX = "*.";
    public static final String PERCENT_S_PLACEHOLDER = "%s";
    public static final String TOKEN_PLACEHOLDER = "{token}";
    public static final String SECRET_PLACEHOLDER = "{secret}";
    public static final String COOKIE_SEPARATOR = "; ";
    private static final Pattern BASE64_FUNCTION_PATTERN = Pattern.compile("base64\\(([^)]*)\\)");

    private final CredentialService credentialService;
    private final EncryptionService encryptionService;

    public record InjectedTarget(URI targetUri, HttpHeaders headers) {}

    public CredentialInjectionService(
            CredentialService credentialService,
            EncryptionService encryptionService) {
        this.credentialService = credentialService;
        this.encryptionService = encryptionService;
    }

    public InjectedTarget inject(String host, URI targetUri, HttpHeaders requestHeaders) {
        if (host == null || targetUri == null) {
            return new InjectedTarget(targetUri, requestHeaders);
        }

        List<HttpInjectionEntity> matches = findMatchingInjections(host);
        if (matches.isEmpty()) {
            return new InjectedTarget(targetUri, requestHeaders);
        }

        HttpHeaders modifiedHeaders = new HttpHeaders();
        if (requestHeaders != null) {
            requestHeaders.forEach((k, v) -> modifiedHeaders.put(k, List.copyOf(v)));
        }

        URI currentUri = targetUri;

        for (HttpInjectionEntity injection : matches) {
            String rawSecret = encryptionService.decrypt(injection.getCredential().getEncryptedSecret());
            if (rawSecret == null || rawSecret.isBlank()) {
                continue;
            }

            String formatted = formatValue(injection.getFormat(), rawSecret);

            switch (injection.getInjectionTarget()) {
                case HEADER -> modifiedHeaders.set(injection.getKey(), formatted);
                case COOKIE -> injectCookie(modifiedHeaders, injection.getKey(), formatted);
                case QUERY -> currentUri = injectQueryParam(currentUri, injection.getKey(), formatted);
            }
        }

        return new InjectedTarget(currentUri, modifiedHeaders);
    }

    private List<HttpInjectionEntity> findMatchingInjections(String host) {
        String lowerHost = host.toLowerCase(Locale.ROOT);
        return credentialService.getActiveHttpInjections().stream()
                .filter(c -> matchesHost(lowerHost, c.getHost()))
                .toList();
    }

    public static boolean matchesHost(String targetHost, String configuredHost) {
        if (configuredHost == null || targetHost == null) {
            return false;
        }
        String cleanConfigured = HttpPolicyValidator.normalizeHostIfValid(configuredHost);
        String cleanTarget = HttpPolicyValidator.normalizeHostIfValid(targetHost);

        if (cleanConfigured.equals(cleanTarget)) {
            return true;
        }

        if (cleanConfigured.startsWith(WILDCARD_HOST_PREFIX) && !HttpPolicyValidator.isIpAddress(cleanTarget)) {
            String domain = cleanConfigured.substring(WILDCARD_HOST_PREFIX.length());
            return cleanTarget.endsWith("." + domain) || cleanTarget.equals(domain);
        }

        return false;
    }

    private static String formatValue(String template, String secret) {
        if (template == null || template.isBlank()) {
            return secret;
        }
        Matcher matcher = BASE64_FUNCTION_PATTERN.matcher(template);
        if (!matcher.find()) {
            return substitutePlaceholders(template, secret);
        }
        StringBuilder result = new StringBuilder(template.length());
        int lastEnd = 0;
        do {
            result.append(substitutePlaceholders(template.substring(lastEnd, matcher.start()), secret));
            String plain = substitutePlaceholders(matcher.group(1), secret);
            result.append(Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8)));
            lastEnd = matcher.end();
        } while (matcher.find());
        result.append(substitutePlaceholders(template.substring(lastEnd), secret));
        return result.toString();
    }

    private static String substitutePlaceholders(String segment, String secret) {
        return segment
                .replace(PERCENT_S_PLACEHOLDER, secret)
                .replace(TOKEN_PLACEHOLDER, secret)
                .replace(SECRET_PLACEHOLDER, secret);
    }

    private void injectCookie(HttpHeaders headers, String cookieName, String value) {
        String existing = headers.getFirst(HttpHeaders.COOKIE);
        String cookiePart = cookieName + "=" + value;
        if (existing == null || existing.isBlank()) {
            headers.set(HttpHeaders.COOKIE, cookiePart);
        } else {
            headers.set(HttpHeaders.COOKIE, existing + COOKIE_SEPARATOR + cookiePart);
        }
    }

    private URI injectQueryParam(URI uri, String key, String value) {
        String encodedKey = encodeQueryParamComponent(key);
        String encodedValue = encodeQueryParamComponent(value);
        return UriComponentsBuilder.fromUri(uri)
                .replaceQueryParam(encodedKey, encodedValue)
                .build(true)
                .toUri();
    }

    private static String encodeQueryParamComponent(String component) {
        return UriUtils.encodeQueryParam(component, StandardCharsets.UTF_8).replace("+", "%2B");
    }
}
