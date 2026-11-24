package ai.whitekey.policy;

import com.google.common.base.CharMatcher;
import com.google.common.net.InetAddresses;
import com.google.common.net.InternetDomainName;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public final class HttpPolicyValidator {
    private static final CharMatcher UNRESERVED_ASCII = CharMatcher.inRange('a', 'z')
            .or(CharMatcher.inRange('A', 'Z'))
            .or(CharMatcher.inRange('0', '9'))
            .or(CharMatcher.anyOf("-._~"));

    private final CelBodyEvaluator bodyEvaluator;

    public HttpPolicyValidator(CelBodyEvaluator bodyEvaluator) {
        this.bodyEvaluator = bodyEvaluator;
    }

    public HttpMethod method(String input) {
        String upper = requireText(input).trim().toUpperCase(Locale.ROOT);
        io.netty.handler.codec.http.HttpMethod.valueOf(upper);
        return HttpMethod.valueOf(upper);
    }

    public List<HttpMethod> methodNames(List<String> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        return input.stream().map(this::method).toList();
    }

    public List<HttpMethod> methods(List<HttpMethod> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        if (input.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("HTTP method must not be null");
        }
        return List.copyOf(input);
    }

    public String host(String input) {
        return normalizeHost(input);
    }

    /**
     * Validates and normalizes an exact DNS hostname or canonical IPv4/IPv6 host literal.
     */
    public static String normalizeHost(String input) {
        requireText(input);
        String raw = input.toLowerCase(Locale.ROOT);
        if (raw.startsWith("[") || raw.endsWith("]")) {
            String unbracketed = stripBrackets(raw);
            if (unbracketed == null) {
                throw new IllegalArgumentException("Invalid IPv6 host literal");
            }
            return canonicalizeIpv6(unbracketed);
        }
        if (raw.indexOf(':') >= 0) {
            return canonicalizeIpv6(raw);
        }
        if (isStrictIpv4(raw)) {
            return raw;
        }
        if (!isValidDnsHost(raw)) {
            throw new IllegalArgumentException(
                    "Hosts must be exact DNS names or canonical IPv4/IPv6 addresses without schemes, ports or wildcards");
        }
        return raw;
    }

    /**
     * Parses an unbracketed IPv6 literal (with optional strict dotted-quad tail) into canonical RFC 5952 form.
     */
    public static String canonicalizeIpv6(String input) {
        InetAddress addr = parseIpv6OrNull(input);
        if (addr == null) {
            throw new IllegalArgumentException("Invalid IPv6 address");
        }
        if (!(addr instanceof Inet6Address)) {
            throw new IllegalArgumentException(
                    "IPv4-mapped IPv6 addresses are not supported; use the IPv4 address directly");
        }
        return InetAddresses.toAddrString(addr);
    }

    /**
     * Returns {@code true} if {@code host} is a valid canonical/unambiguous IPv4 or IPv6 literal.
     */
    public static boolean isIpAddress(String host) {
        if (isStrictIpv4(host)) {
            return true;
        }
        if (host == null || host.indexOf(':') < 0) {
            return false;
        }
        String unbracketed = stripBrackets(host);
        return tryCanonicalizeIpv6(unbracketed != null ? unbracketed : host) != null;
    }

    /**
     * Trims and canonicalizes valid DNS/IP hosts (preserving {@code *.domain} wildcards in lowercase);
     * returns trimmed lowercase input if not a valid host literal.
     */
    public static String normalizeHostIfValid(String rawHost) {
        if (rawHost == null) {
            return "";
        }
        String lower = rawHost.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty() || lower.startsWith("*.")) {
            return lower;
        }
        if (lower.startsWith("[") || lower.endsWith("]")) {
            String ipv6 = tryCanonicalizeIpv6(stripBrackets(lower));
            return ipv6 != null ? ipv6 : lower;
        }
        if (lower.indexOf(':') >= 0) {
            String ipv6 = tryCanonicalizeIpv6(lower);
            return ipv6 != null ? ipv6 : lower;
        }
        return lower;
    }

    private static String stripBrackets(String value) {
        return (value.startsWith("[") && value.endsWith("]"))
                ? value.substring(1, value.length() - 1)
                : null;
    }

    private static String tryCanonicalizeIpv6(String input) {
        InetAddress addr = parseIpv6OrNull(input);
        return addr instanceof Inet6Address ? InetAddresses.toAddrString(addr) : null;
    }

    private static InetAddress parseIpv6OrNull(String input) {
        if (input == null || input.indexOf(':') < 0 || input.indexOf('%') >= 0
                || !InetAddresses.isInetAddress(input)) {
            return null;
        }
        return InetAddresses.forString(input);
    }

    private static boolean isStrictIpv4(String input) {
        return input != null && input.indexOf(':') < 0 && InetAddresses.isInetAddress(input);
    }

    private static boolean isValidDnsHost(String raw) {
        return !raw.endsWith(".")
                && raw.indexOf('_') < 0
                && CharMatcher.ascii().matchesAllOf(raw)
                && InternetDomainName.isValid(raw);
    }

    public List<String> hosts(List<String> input) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("At least one host is required");
        }
        List<String> hosts = input.stream().map(this::host).toList();
        if (new HashSet<>(hosts).size() != hosts.size()) {
            throw new IllegalArgumentException("Duplicate hosts");
        }
        return hosts;
    }

    public List<Integer> ports(List<Integer> ports) {
        if (ports == null || ports.isEmpty()) {
            return List.of();
        }
        for (Integer port : ports) {
            if (port == null || port < 1 || port > 65535) {
                throw new IllegalArgumentException("Port must be between 1 and 65535: " + port);
            }
        }
        return List.copyOf(ports);
    }

    public String path(String input) {
        validatePath(requireText(input), false);
        return input;
    }

    public void query(String rawQuery) {
        parseQueryParams(rawQuery);
    }

    public static Map<String, List<String>> parseQueryParams(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> params = new LinkedHashMap<>();
        for (String segment : rawQuery.split("&", -1)) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("Invalid empty query parameter");
            }
            int eq = segment.indexOf('=');
            String key = decodeStrictUtf8(eq >= 0 ? segment.substring(0, eq) : segment, "query parameter");
            if (key.isBlank()) {
                throw new IllegalArgumentException("Query param name must not be blank");
            }
            List<String> values = params.get(key);
            if (values == null) {
                values = new ArrayList<>();
                params.put(key, values);
                if (eq >= 0) {
                    values.add(decodeStrictUtf8(segment.substring(eq + 1), "query parameter"));
                }
            } else if (eq >= 0) {
                if (values.isEmpty()) {
                    values.add("");
                }
                values.add(decodeStrictUtf8(segment.substring(eq + 1), "query parameter"));
            } else if (!values.isEmpty()) {
                values.add("");
            }
        }
        params.replaceAll((k, v) -> List.copyOf(v));
        return Collections.unmodifiableMap(params);
    }

    public List<String> pathPatterns(List<String> input) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("At least one path pattern is required");
        }
        Set<String> seen = new HashSet<>(input.size());
        for (String path : input) {
            requireText(path);
            if (!seen.add(path)) {
                throw new IllegalArgumentException("Duplicate values");
            }
            validatePath(path, true);
        }
        return List.copyOf(input);
    }

    public Map<String, List<String>> queryParams(Map<String, List<String>> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> params = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : input.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Query param name must not be blank");
            }
            List<String> values = entry.getValue();
            params.put(key, values != null ? List.copyOf(values) : List.of());
        }
        return Collections.unmodifiableMap(params);
    }

    public HttpHeaders headers(Map<String, List<String>> input) {
        if (input == null || input.isEmpty()) {
            return HttpHeaders.EMPTY;
        }
        HttpHeaders headers = new HttpHeaders();
        for (Map.Entry<String, List<String>> entry : input.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Header name must not be blank");
            }
            List<String> values = entry.getValue();
            if (values != null) {
                for (String v : values) {
                    if (v == null) {
                        throw new IllegalArgumentException("Header value must not be null");
                    }
                    headers.add(name, v);
                }
            }
        }
        return HttpHeaders.readOnlyHttpHeaders(headers);
    }

    public String bodyCondition(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String trimmed = input.trim();
        bodyEvaluator.validate(trimmed);
        return trimmed;
    }

    private static String requireText(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Expected a nonempty string");
        }
        return value;
    }

    private void validatePath(String input, boolean pattern) {
        String path = pattern && input.endsWith("/*") ? input.substring(0, input.length() - 1) : input;
        if (!path.startsWith("/")
                || path.contains("//")
                || path.indexOf('?') >= 0
                || path.indexOf('#') >= 0
                || !CharMatcher.ascii().matchesAllOf(path)) {
            throw new IllegalArgumentException(
                    "Paths must be absolute ASCII paths; only a trailing /* wildcard is supported in rules");
        }
        try {
            URI.create("http://x" + path);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Paths must be absolute ASCII paths; only a trailing /* wildcard is supported in rules", e);
        }
        rejectDotSegments(path);
        if (path.indexOf('%') >= 0) {
            String decoded = decodeStrictUtf8(path, "path");
            if (decoded.indexOf('\\') >= 0) {
                throw new IllegalArgumentException("Noncanonical encoded path");
            }
            for (int i = path.indexOf('%'); i >= 0; i = path.indexOf('%', i + 3)) {
                String ch = UriUtils.decode(path.substring(i, i + 3), StandardCharsets.UTF_8);
                if (ch.length() == 1 && UNRESERVED_ASCII.matches(ch.charAt(0))) {
                    throw new IllegalArgumentException("Noncanonical encoded path");
                }
            }
            rejectDotSegments(decoded);
        }
    }

    private static String decodeStrictUtf8(String input, String context) {
        if (input.indexOf('%') < 0) {
            return input;
        }
        String decoded = UriUtils.decode(input, StandardCharsets.UTF_8);
        if (decoded.indexOf('\uFFFD') >= 0) {
            throw new IllegalArgumentException("Invalid UTF-8 " + context);
        }
        if (decoded.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Noncanonical encoded " + context);
        }
        return decoded;
    }

    private static void rejectDotSegments(String path) {
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Dot segments are not supported");
            }
        }
    }
}
