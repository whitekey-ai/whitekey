package ai.whitekey.proxy;

import ai.whitekey.api.dto.ProtocolConstants;
import ai.whitekey.policy.HttpPolicyValidator;

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

    public static ProxyTarget parseForwardRequest(String rawTarget, String rawQuery) {
        if (rawTarget == null || rawTarget.isBlank()) {
            throw new IllegalArgumentException(
                    "Absolute target URL is required (configure HTTP_PROXY / HTTPS_PROXY)");
        }
        String scheme;
        String rest;
        if (rawTarget.regionMatches(true, 0, SCHEME_HTTPS_PREFIX, 0, SCHEME_HTTPS_PREFIX.length())) {
            scheme = ProtocolConstants.HTTPS;
            rest = rawTarget.substring(SCHEME_HTTPS_PREFIX.length());
        } else if (rawTarget.regionMatches(true, 0, SCHEME_HTTP_PREFIX, 0, SCHEME_HTTP_PREFIX.length())) {
            scheme = ProtocolConstants.HTTP;
            rest = rawTarget.substring(SCHEME_HTTP_PREFIX.length());
        } else {
            throw new IllegalArgumentException(
                    "Absolute target URL is required (configure HTTP_PROXY / HTTPS_PROXY)");
        }

        if (rest.isEmpty() || rest.startsWith("/")) {
            throw new IllegalArgumentException("Target host is missing");
        }

        int slashIndex = rest.indexOf('/');
        String authority = slashIndex >= 0 ? rest.substring(0, slashIndex) : rest;
        String targetPath = slashIndex >= 0 ? rest.substring(slashIndex) : "/";
        return parseAuthority(authority, scheme, targetPath, rawQuery, defaultPort(scheme));
    }

    public static ProxyTarget forTunnelRequest(ProxyTarget tunnelTarget, String rawPath, String rawQuery) {
        if (rawPath == null || rawPath.isEmpty() || rawPath.startsWith("/")) {
            String path = (rawPath == null || rawPath.isEmpty()) ? "/" : rawPath;
            return new ProxyTarget(
                    tunnelTarget.scheme(),
                    tunnelTarget.host(),
                    tunnelTarget.port(),
                    tunnelTarget.explicitPort(),
                    path,
                    rawQuery);
        }
        if (rawPath.regionMatches(true, 0, SCHEME_HTTPS_PREFIX, 0, SCHEME_HTTPS_PREFIX.length())
                || rawPath.regionMatches(true, 0, SCHEME_HTTP_PREFIX, 0, SCHEME_HTTP_PREFIX.length())) {
            ProxyTarget parsed = parseForwardRequest(rawPath, rawQuery);
            if (!parsed.host().equals(tunnelTarget.host()) || parsed.port() != tunnelTarget.port()) {
                throw new IllegalArgumentException("Request target does not match CONNECT tunnel host/port");
            }
            return parsed;
        }
        throw new IllegalArgumentException("Invalid request target inside HTTPS tunnel");
    }

    public static ProxyTarget fromUri(URI url) {
        if (url == null) {
            return new ProxyTarget(ProtocolConstants.HTTP, "", DEFAULT_HTTP_PORT, false, "/", null);
        }
        String scheme = url.getScheme() != null ? url.getScheme().toLowerCase(Locale.ROOT) : ProtocolConstants.HTTP;
        String host = url.getHost() != null ? HttpPolicyValidator.normalizeHostIfValid(url.getHost()) : "";
        int port = url.getPort() != -1 ? url.getPort() : defaultPort(scheme);
        String rawPath = url.getRawPath();
        String path = (rawPath != null && !rawPath.isEmpty()) ? rawPath : "/";
        return new ProxyTarget(scheme, host, port, url.getPort() != -1, path, url.getRawQuery());
    }

    public ProxyTarget withHost(String canonicalHost) {
        return new ProxyTarget(scheme, canonicalHost, port, explicitPort, path, rawQuery);
    }

    private static int defaultPort(String scheme) {
        return ProtocolConstants.HTTPS.equalsIgnoreCase(scheme) ? DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;
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
        return parseAuthority(trimmed, ProtocolConstants.HTTPS, "/", null, DEFAULT_HTTPS_PORT);
    }

    private static ProxyTarget parseAuthority(
            String authority,
            String scheme,
            String targetPath,
            String rawQuery,
            int defaultPort) {
        if (authority.contains("@") || authority.isEmpty()) {
            throw new IllegalArgumentException("Invalid target host");
        }

        String host;
        int port;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close <= 1) {
                throw new IllegalArgumentException("Invalid target host");
            }
            host = HttpPolicyValidator.canonicalizeIpv6(authority.substring(1, close));
            String rest = authority.substring(close + 1);
            if (rest.isEmpty()) {
                port = defaultPort;
            } else if (rest.startsWith(":")) {
                port = parsePort(rest.substring(1));
            } else {
                throw new IllegalArgumentException("Invalid target host");
            }
        } else {
            if (authority.indexOf('[') >= 0 || authority.indexOf(']') >= 0) {
                throw new IllegalArgumentException("Invalid target host");
            }
            int colon = authority.lastIndexOf(':');
            if (colon > 0 && authority.indexOf(':') == colon) {
                host = authority.substring(0, colon).toLowerCase(Locale.ROOT);
                port = parsePort(authority.substring(colon + 1));
            } else if (colon >= 0) {
                throw new IllegalArgumentException("Invalid target host");
            } else {
                host = authority.toLowerCase(Locale.ROOT);
                port = defaultPort;
            }
        }

        return new ProxyTarget(scheme, host, port, port != defaultPort, targetPath, rawQuery);
    }

    private static int parsePort(String rawPort) {
        if (rawPort.isEmpty()) {
            throw new IllegalArgumentException("Invalid target port");
        }
        for (int i = 0; i < rawPort.length(); i++) {
            char c = rawPort.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("Invalid target port");
            }
        }
        int port;
        try {
            port = Integer.parseInt(rawPort);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid target port");
        }
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException("Invalid target port");
        }
        return port;
    }

    public URI toUri(String effectiveRawQuery) {
        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://");
        if (host.indexOf(':') >= 0) {
            sb.append('[').append(host).append(']');
        } else {
            sb.append(host);
        }
        if (explicitPort) {
            sb.append(':').append(port);
        }
        sb.append(path);
        if (effectiveRawQuery != null && !effectiveRawQuery.isEmpty()) {
            sb.append('?').append(effectiveRawQuery);
        }
        return URI.create(sb.toString());
    }
}
