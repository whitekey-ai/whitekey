package ai.whitekey.service;

import ai.whitekey.entity.HttpCredentialEntity;
import ai.whitekey.entity.HttpCredentialTargetType;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Locale;

@Service
public class CredentialInjectionService {

    public static final String WILDCARD_HOST_PREFIX = "*.";
    public static final String PERCENT_S_PLACEHOLDER = "%s";
    public static final String TOKEN_PLACEHOLDER = "{token}";
    public static final String SECRET_PLACEHOLDER = "{secret}";
    public static final String COOKIE_SEPARATOR = "; ";

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

        List<HttpCredentialEntity> matches = findMatchingCredentials(host);
        if (matches.isEmpty()) {
            return new InjectedTarget(targetUri, requestHeaders);
        }

        HttpHeaders modifiedHeaders = new HttpHeaders();
        if (requestHeaders != null) {
            requestHeaders.forEach((k, v) -> modifiedHeaders.put(k, List.copyOf(v)));
        }

        URI currentUri = targetUri;

        for (HttpCredentialEntity cred : matches) {
            String rawSecret = encryptionService.decrypt(cred.getEncryptedSecret());
            if (rawSecret == null || rawSecret.isBlank()) {
                continue;
            }

            String formatted = formatValue(cred.getFormatTemplate(), rawSecret);

            switch (cred.getTargetType()) {
                case HEADER -> modifiedHeaders.set(cred.getTargetKey(), formatted);
                case COOKIE -> injectCookie(modifiedHeaders, cred.getTargetKey(), formatted);
                case QUERY -> currentUri = injectQueryParam(currentUri, cred.getTargetKey(), formatted);
            }
        }

        return new InjectedTarget(currentUri, modifiedHeaders);
    }

    private List<HttpCredentialEntity> findMatchingCredentials(String host) {
        String lowerHost = host.toLowerCase(Locale.ROOT);
        return credentialService.getActiveHttpCredentials().stream()
                .filter(c -> matchesHost(lowerHost, c.getHost()))
                .toList();
    }

    public static boolean matchesHost(String targetHost, String configuredHost) {
        if (configuredHost == null || targetHost == null) {
            return false;
        }
        String cleanConfigured = configuredHost.trim().toLowerCase(Locale.ROOT);
        String cleanTarget = targetHost.trim().toLowerCase(Locale.ROOT);

        if (cleanConfigured.equals(cleanTarget)) {
            return true;
        }

        if (cleanConfigured.startsWith(WILDCARD_HOST_PREFIX)) {
            String domain = cleanConfigured.substring(WILDCARD_HOST_PREFIX.length());
            return cleanTarget.endsWith("." + domain) || cleanTarget.equals(domain);
        }

        return false;
    }

    private String formatValue(String template, String secret) {
        if (template == null || template.isBlank()) {
            return secret;
        }
        if (template.contains(PERCENT_S_PLACEHOLDER)) {
            return String.format(template, secret);
        }
        return template.replace(TOKEN_PLACEHOLDER, secret).replace(SECRET_PLACEHOLDER, secret);
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
        return UriComponentsBuilder.fromUri(uri)
                .queryParam(key, value)
                .build(true)
                .toUri();
    }
}
