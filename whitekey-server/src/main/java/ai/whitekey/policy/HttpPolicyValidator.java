package ai.whitekey.policy;

import ai.whitekey.proxy.ProxyTarget;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;

@Component
public final class HttpPolicyValidator {
    private static final int MAX_DNS_HOST_LENGTH = 253;
    private static final int HEX_RADIX = 16;
    private static final int ASCII_PRINTABLE_MIN = 0x20;
    private static final int ASCII_DEL = 0x7F;
    private static final int ASCII_BOUNDARY = 128;
    private static final int IPV4_OCTET_COUNT = 4;
    private static final int MAX_IPV4_OCTET = 255;
    private static final int IPV6_GROUP_COUNT = 8;
    private static final int IPV6_EMBEDDED_IPV4_GROUPS = 2;
    private static final int IPV6_BYTE_SHIFT = 8;
    private static final int MIN_IPV6_COMPRESS_RUN = 2;
    private static final int IPV4_MAPPED_PREFIX_ZERO_GROUPS = 5;
    private static final int IPV4_MAPPED_MARKER_GROUP = 0xFFFF;

    private static final Pattern STRICT_IPV4_OCTET_PATTERN = Pattern.compile("0|[1-9][0-9]{0,2}");
    private static final Pattern IPV6_HEX_GROUP_PATTERN = Pattern.compile("[0-9a-f]{1,4}");
    private static final Pattern NUMERIC_OR_HEX_LABEL_PATTERN = Pattern.compile("[0-9]+|0x[0-9a-f]+");
    private static final Pattern DNS_LABEL_PATTERN = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern HTTP_METHOD_PATTERN = Pattern.compile("[a-z0-9!#$%&'*+.^_`|~\\-]+");
    private static final Pattern ASCII_PATH_PATTERN = Pattern.compile("/[A-Za-z0-9%/_~!$&'()+,;=:@.\\-]*");
    private static final Pattern HEX_BYTE_PATTERN = Pattern.compile("[0-9A-Fa-f]{2}");

    private final CelBodyEvaluator bodyEvaluator;

    public HttpPolicyValidator(CelBodyEvaluator bodyEvaluator) {
        this.bodyEvaluator = bodyEvaluator;
    }

    public HttpMethod method(String input) {
        String method = requireText(input).trim();
        if (!validMethod(method.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Invalid HTTP method");
        }
        return HttpMethod.valueOf(method.toUpperCase(Locale.ROOT));
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
        for (HttpMethod m : input) {
            if (m == null) {
                throw new IllegalArgumentException("HTTP method must not be null");
            }
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
            if (!raw.startsWith("[") || !raw.endsWith("]")) {
                throw new IllegalArgumentException("Invalid IPv6 host literal");
            }
            return canonicalizeIpv6(raw.substring(1, raw.length() - 1));
        }
        if (raw.indexOf(':') >= 0) {
            return canonicalizeIpv6(raw);
        }
        String ipv4 = tryParseStrictIpv4(raw);
        if (ipv4 != null) {
            return ipv4;
        }
        if (raw.length() > MAX_DNS_HOST_LENGTH) {
            throw new IllegalArgumentException("Expected an exact DNS host name or IP address");
        }
        String[] labels = raw.split("[.]", -1);
        for (String label : labels) {
            if (!DNS_LABEL_PATTERN.matcher(label).matches()) {
                throw new IllegalArgumentException(
                        "Hosts must be exact DNS names or canonical IPv4/IPv6 addresses without schemes, ports or wildcards");
            }
        }
        if (NUMERIC_OR_HEX_LABEL_PATTERN.matcher(labels[labels.length - 1]).matches()) {
            throw new IllegalArgumentException(
                    "Hosts must be exact DNS names or canonical IPv4/IPv6 addresses without schemes, ports or wildcards");
        }
        return raw;
    }

    /**
     * Parses an unbracketed IPv6 literal (with optional strict dotted-quad tail) into canonical RFC 5952 form.
     */
    public static String canonicalizeIpv6(String input) {
        if (input == null || input.isEmpty()
                || input.indexOf('%') >= 0
                || input.indexOf('[') >= 0
                || input.indexOf(']') >= 0) {
            throw new IllegalArgumentException("Invalid IPv6 address");
        }
        String raw = input.toLowerCase(Locale.ROOT);
        int doubleColon = raw.indexOf("::");
        if (doubleColon >= 0 && raw.indexOf("::", doubleColon + 1) >= 0) {
            throw new IllegalArgumentException("Invalid IPv6 address");
        }

        boolean hasEmbeddedIpv4 = raw.indexOf('.') >= 0;
        String hexPart = raw;
        int tailGroup0 = 0;
        int tailGroup1 = 0;
        if (hasEmbeddedIpv4) {
            int lastColon = raw.lastIndexOf(':');
            if (lastColon < 0) {
                throw new IllegalArgumentException("Invalid IPv6 address");
            }
            int[] octets = parseStrictIpv4Octets(raw.substring(lastColon + 1));
            if (octets == null) {
                throw new IllegalArgumentException("Invalid embedded IPv4 address in IPv6 literal");
            }
            tailGroup0 = (octets[0] << IPV6_BYTE_SHIFT) | octets[1];
            tailGroup1 = (octets[2] << IPV6_BYTE_SHIFT) | octets[3];
            hexPart = (doubleColon >= 0 && lastColon == doubleColon + 1)
                    ? raw.substring(0, lastColon + 1)
                    : raw.substring(0, lastColon);
            if (hexPart.isEmpty()) {
                throw new IllegalArgumentException("Invalid IPv6 address");
            }
        }

        int targetHexGroups = hasEmbeddedIpv4 ? IPV6_GROUP_COUNT - IPV6_EMBEDDED_IPV4_GROUPS : IPV6_GROUP_COUNT;
        int[] groups = new int[IPV6_GROUP_COUNT];
        parseIpv6HexGroups(hexPart, targetHexGroups, groups);
        if (hasEmbeddedIpv4) {
            groups[targetHexGroups] = tailGroup0;
            groups[targetHexGroups + 1] = tailGroup1;
        }
        if (isIpv4MappedIpv6(groups)) {
            throw new IllegalArgumentException(
                    "IPv4-mapped IPv6 addresses are not supported; use the IPv4 address directly");
        }
        return formatCanonicalIpv6(groups);
    }

    /**
     * Returns {@code true} if {@code host} is a valid canonical/unambiguous IPv4 or IPv6 literal.
     */
    public static boolean isIpAddress(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (tryParseStrictIpv4(host) != null) {
            return true;
        }
        if (host.indexOf(':') < 0) {
            return false;
        }
        String candidate = (host.startsWith("[") && host.endsWith("]"))
                ? host.substring(1, host.length() - 1)
                : host;
        try {
            canonicalizeIpv6(candidate);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /**
     * Trims and canonicalizes valid DNS/IP hosts (preserving {@code *.domain} wildcards in lowercase);
     * returns trimmed lowercase input if not a valid host literal.
     */
    public static String normalizeHostIfValid(String rawHost) {
        if (rawHost == null) {
            return "";
        }
        String trimmed = rawHost.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.startsWith("*.")) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        try {
            return normalizeHost(trimmed);
        } catch (IllegalArgumentException ignored) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
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
            if (port == null || port < ProxyTarget.MIN_PORT || port > ProxyTarget.MAX_PORT) {
                throw new IllegalArgumentException(
                        "Port must be between " + ProxyTarget.MIN_PORT + " and " + ProxyTarget.MAX_PORT + ": " + port);
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
        Set<String> valuelessKeys = new HashSet<>();
        for (String segment : rawQuery.split("&", -1)) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("Invalid empty query parameter");
            }
            int eq = segment.indexOf('=');
            String rawKey = eq >= 0 ? segment.substring(0, eq) : segment;
            String key = decodeQueryComponent(rawKey);
            if (key.isBlank()) {
                throw new IllegalArgumentException("Query param name must not be blank");
            }
            List<String> values = params.computeIfAbsent(key, ignored -> new ArrayList<>());
            if (eq >= 0) {
                if (valuelessKeys.remove(key)) {
                    values.add("");
                }
                values.add(decodeQueryComponent(segment.substring(eq + 1)));
            } else if (!values.isEmpty()) {
                values.add("");
            } else {
                valuelessKeys.add(key);
            }
        }
        Map<String, List<String>> immutable = new LinkedHashMap<>();
        params.forEach((k, v) -> immutable.put(k, List.copyOf(v)));
        return Collections.unmodifiableMap(immutable);
    }

    public List<String> pathPatterns(List<String> input) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("At least one path pattern is required");
        }
        List<String> paths = values(input);
        paths.forEach(path -> validatePath(path, true));
        return paths;
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

    private List<String> values(List<String> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        input.forEach(HttpPolicyValidator::requireText);
        if (new HashSet<>(input).size() != input.size()) {
            throw new IllegalArgumentException("Duplicate values");
        }
        return List.copyOf(input);
    }

    private static String requireText(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Expected a nonempty string");
        }
        return value;
    }

    private static boolean validMethod(String method) {
        return HTTP_METHOD_PATTERN.matcher(method).matches();
    }

    private void validatePath(String input, boolean pattern) {
        String path =
                pattern && input.endsWith("/*") ? input.substring(0, input.length() - 1) : input;
        if (!ASCII_PATH_PATTERN.matcher(path).matches()
                || path.contains("//")) {
            throw new IllegalArgumentException(
                    "Paths must be absolute ASCII paths; only a trailing /* wildcard is supported"
                            + " in rules");
        }
        rejectDotSegments(path);
        if (path.contains("%")) {
            String decodedPath = decodePercentUtf8(
                    path, HEX_BYTE_PATTERN, HttpPolicyValidator::isRejectedEncodedPathByte, "path");
            rejectDotSegments(decodedPath);
        }
    }

    private static String decodeQueryComponent(String input) {
        if (input.indexOf('%') < 0) {
            return input;
        }
        return decodePercentUtf8(input, HEX_BYTE_PATTERN, HttpPolicyValidator::isControlByte, "query parameter");
    }

    private static String decodePercentUtf8(
            String input, Pattern hexPattern, IntPredicate rejectedByte, String context) {
        var decoded = new ByteArrayOutputStream(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c != '%') {
                if (c < ASCII_BOUNDARY) {
                    decoded.write(c);
                } else {
                    decoded.writeBytes(Character.toString(c).getBytes(StandardCharsets.UTF_8));
                }
                continue;
            }
            if (i + 2 >= input.length() || !hexPattern.matcher(input.substring(i + 1, i + 3)).matches()) {
                throw new IllegalArgumentException("Invalid encoded " + context);
            }
            int b = Integer.parseInt(input.substring(i + 1, i + 3), HEX_RADIX);
            i += 2;
            if (rejectedByte.test(b)) {
                throw new IllegalArgumentException("Noncanonical encoded " + context);
            }
            decoded.write(b);
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded.toByteArray()))
                    .toString();
        } catch (CharacterCodingException ignored) {
            throw new IllegalArgumentException("Invalid UTF-8 " + context);
        }
    }

    private static boolean isControlByte(int b) {
        return b < ASCII_PRINTABLE_MIN || b == ASCII_DEL;
    }

    private static boolean isRejectedEncodedPathByte(int b) {
        return isControlByte(b) || b == '\\' || isUnreservedAscii(b);
    }

    private static void rejectDotSegments(String path) {
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Dot segments are not supported");
            }
        }
    }

    private static boolean isUnreservedAscii(int b) {
        return (b >= 'A' && b <= 'Z')
                || (b >= 'a' && b <= 'z')
                || (b >= '0' && b <= '9')
                || b == '-' || b == '.' || b == '_' || b == '~';
    }

    private static String tryParseStrictIpv4(String raw) {
        return parseStrictIpv4Octets(raw) != null ? raw : null;
    }

    private static int[] parseStrictIpv4Octets(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String[] parts = raw.split("[.]", -1);
        if (parts.length != IPV4_OCTET_COUNT) {
            return null;
        }
        int[] octets = new int[IPV4_OCTET_COUNT];
        for (int i = 0; i < IPV4_OCTET_COUNT; i++) {
            String part = parts[i];
            if (!STRICT_IPV4_OCTET_PATTERN.matcher(part).matches()) {
                return null;
            }
            int value = Integer.parseInt(part);
            if (value > MAX_IPV4_OCTET) {
                return null;
            }
            octets[i] = value;
        }
        return octets;
    }

    private static void parseIpv6HexGroups(String hexPart, int targetHexGroups, int[] groups) {
        int doubleColon = hexPart.indexOf("::");
        if (doubleColon < 0) {
            String[] all = hexPart.split(":", -1);
            if (all.length != targetHexGroups) {
                throw new IllegalArgumentException("Invalid IPv6 address");
            }
            for (int i = 0; i < all.length; i++) {
                groups[i] = parseIpv6HexGroup(all[i]);
            }
            return;
        }

        String leftPart = hexPart.substring(0, doubleColon);
        String rightPart = hexPart.substring(doubleColon + 2);
        String[] left = leftPart.isEmpty() ? new String[0] : leftPart.split(":", -1);
        String[] right = rightPart.isEmpty() ? new String[0] : rightPart.split(":", -1);
        int zeroFill = targetHexGroups - left.length - right.length;
        if (zeroFill < 1) {
            throw new IllegalArgumentException("Invalid IPv6 address");
        }
        int index = 0;
        for (String group : left) {
            groups[index++] = parseIpv6HexGroup(group);
        }
        index += zeroFill;
        for (String group : right) {
            groups[index++] = parseIpv6HexGroup(group);
        }
    }

    private static int parseIpv6HexGroup(String group) {
        if (!IPV6_HEX_GROUP_PATTERN.matcher(group).matches()) {
            throw new IllegalArgumentException("Invalid IPv6 address");
        }
        return Integer.parseInt(group, HEX_RADIX);
    }

    private static boolean isIpv4MappedIpv6(int[] groups) {
        for (int i = 0; i < IPV4_MAPPED_PREFIX_ZERO_GROUPS; i++) {
            if (groups[i] != 0) {
                return false;
            }
        }
        return groups[IPV4_MAPPED_PREFIX_ZERO_GROUPS] == IPV4_MAPPED_MARKER_GROUP;
    }

    private static String formatCanonicalIpv6(int[] groups) {
        int bestStart = -1;
        int bestLen = 0;
        int curStart = -1;
        int curLen = 0;
        for (int i = 0; i < IPV6_GROUP_COUNT; i++) {
            if (groups[i] == 0) {
                if (curLen == 0) {
                    curStart = i;
                }
                curLen++;
                if (curLen > bestLen) {
                    bestLen = curLen;
                    bestStart = curStart;
                }
            } else {
                curLen = 0;
            }
        }
        if (bestLen < MIN_IPV6_COMPRESS_RUN) {
            bestStart = -1;
            bestLen = 0;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < IPV6_GROUP_COUNT; i++) {
            if (i == bestStart) {
                sb.append("::");
                i += bestLen - 1;
                continue;
            }
            if (i > 0 && i != bestStart + bestLen) {
                sb.append(':');
            }
            sb.append(Integer.toHexString(groups[i]));
        }
        return sb.toString();
    }
}
