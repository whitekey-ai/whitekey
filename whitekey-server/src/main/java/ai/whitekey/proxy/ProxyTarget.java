package ai.whitekey.proxy;

import ai.whitekey.api.dto.ProtocolConstants;

import java.net.URI;
import java.util.Locale;

public record ProxyTarget(
        String scheme,
        String host,
        int port,
        boolean explicitPort,
        String path,
        String rawQuery) {

    public static final int DEFAULT_HTTP_PORT = 80;
    public static final int DEFAULT_HTTPS_PORT = 443;
    public static final int MIN_PORT = 1;
    public static final int MAX_PORT = 65535;

    private static final String SCHEME_HTTPS_PREFIX = ProtocolConstants.HTTPS + "://";
    private static final String SCHEME_HTTP_PREFIX = ProtocolConstants.HTTP + "://";
    private static final String SCHEME_HTTPS_SINGLE_SLASH = ProtocolConstants.HTTPS + ":/";
    private static final String SCHEME_HTTP_SINGLE_SLASH = ProtocolConstants.HTTP + ":/";

    public static ProxyTarget parse(String rawPath, String rawQuery) {
        if (rawPath == null || rawPath.isEmpty() || "/".equals(rawPath)) {
            throw new IllegalArgumentException(
                    "Target URL is required, e.g. /https://api.github.com/user");
        }
        String rest = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        String scheme = ProtocolConstants.HTTPS;

        if (rest.regionMatches(true, 0, SCHEME_HTTPS_PREFIX, 0, SCHEME_HTTPS_PREFIX.length())) {
            scheme = ProtocolConstants.HTTPS;
            rest = rest.substring(SCHEME_HTTPS_PREFIX.length());
        } else if (rest.regionMatches(true, 0, SCHEME_HTTP_PREFIX, 0, SCHEME_HTTP_PREFIX.length())) {
            scheme = ProtocolConstants.HTTP;
            rest = rest.substring(SCHEME_HTTP_PREFIX.length());
        } else if (rest.regionMatches(true, 0, SCHEME_HTTPS_SINGLE_SLASH, 0, SCHEME_HTTPS_SINGLE_SLASH.length())) {
            scheme = ProtocolConstants.HTTPS;
            rest = rest.substring(SCHEME_HTTPS_SINGLE_SLASH.length());
        } else if (rest.regionMatches(true, 0, SCHEME_HTTP_SINGLE_SLASH, 0, SCHEME_HTTP_SINGLE_SLASH.length())) {
            scheme = ProtocolConstants.HTTP;
            rest = rest.substring(SCHEME_HTTP_SINGLE_SLASH.length());
        }

        if (rest.isEmpty() || rest.startsWith("/")) {
            throw new IllegalArgumentException("Target host is missing");
        }

        int slashIndex = rest.indexOf('/');
        String authority = slashIndex >= 0 ? rest.substring(0, slashIndex) : rest;
        String targetPath = slashIndex >= 0 ? rest.substring(slashIndex) : "/";
        int defaultPort = ProtocolConstants.HTTPS.equalsIgnoreCase(scheme) ? DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;
        return parseAuthority(authority, scheme, targetPath, rawQuery, defaultPort, false);
    }

    /**
     * Parses an HTTP {@code CONNECT} authority-form target ({@code host:port}).
     */
    public static ProxyTarget parseConnect(String rawAuthority) {
        if (rawAuthority == null || rawAuthority.isBlank()) {
            throw new IllegalArgumentException("CONNECT target is required, e.g. example.com:443");
        }
        String trimmed = rawAuthority.trim();
        if (trimmed.contains("/") || trimmed.contains("?") || trimmed.contains("#")) {
            throw new IllegalArgumentException("CONNECT target must be authority-form (host:port)");
        }
        return parseAuthority(trimmed, ProtocolConstants.HTTPS, "/", null, DEFAULT_HTTPS_PORT, true);
    }

    private static ProxyTarget parseAuthority(
            String authority,
            String scheme,
            String targetPath,
            String rawQuery,
            int defaultPort,
            boolean forceExplicitPort) {
        if (authority.contains("@") || authority.isEmpty()) {
            throw new IllegalArgumentException("Invalid target host");
        }

        String host;
        int port;
        boolean explicitPort;
        int colon = authority.lastIndexOf(':');
        if (colon > 0 && authority.indexOf(':') == colon) {
            host = authority.substring(0, colon).toLowerCase(Locale.ROOT);
            try {
                port = Integer.parseInt(authority.substring(colon + 1));
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Invalid target port");
            }
            if (port < MIN_PORT || port > MAX_PORT) {
                throw new IllegalArgumentException("Invalid target port");
            }
            explicitPort = true;
        } else if (colon >= 0) {
            throw new IllegalArgumentException("Invalid target host");
        } else {
            host = authority.toLowerCase(Locale.ROOT);
            port = defaultPort;
            explicitPort = forceExplicitPort;
        }

        return new ProxyTarget(scheme, host, port, explicitPort, targetPath, rawQuery);
    }

    public URI toUri(String effectiveRawQuery) {
        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(host);
        if (explicitPort) {
            sb.append(':').append(port);
        }
        sb.append(path);
        if (effectiveRawQuery != null && !effectiveRawQuery.isEmpty()) {
            sb.append('?').append(effectiveRawQuery);
        }
        return URI.create(sb.toString());
    }

    public String rewriteRedirectLocation(String proxyBaseUrl, String location) {
        if (location == null || location.isBlank()) {
            return location;
        }
        String base =
                proxyBaseUrl.endsWith("/")
                        ? proxyBaseUrl.substring(0, proxyBaseUrl.length() - 1)
                        : proxyBaseUrl;
        String lower = location.toLowerCase(Locale.ROOT);
        if (lower.startsWith(SCHEME_HTTPS_PREFIX) || lower.startsWith(SCHEME_HTTP_PREFIX)) {
            return base + "/" + location;
        }
        if (location.startsWith("/")) {
            String authority = explicitPort ? host + ":" + port : host;
            return base + "/" + scheme + "://" + authority + location;
        }
        return location;
    }
}
