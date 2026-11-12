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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public final class HttpPolicyValidator {
    private static final int MAX_DNS_HOST_LENGTH = 253;
    private static final int HEX_RADIX = 16;
    private static final int ASCII_BOUNDARY = 128;

    private static final Pattern IPV4_PATTERN = Pattern.compile("[0-9]+([.][0-9]+){3}");
    private static final Pattern DNS_LABEL_PATTERN = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern HTTP_METHOD_PATTERN = Pattern.compile("[a-z0-9!#$%&'*+.^_`|~\\-]+");
    private static final Pattern ASCII_PATH_PATTERN = Pattern.compile("/[A-Za-z0-9%/_~!$&'()+,;=:@.\\-]*");
    private static final Pattern HEX_BYTE_PATTERN = Pattern.compile("[0-9A-F]{2}");

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
        requireText(input);
        String host = input.toLowerCase(Locale.ROOT);
        if (host.length() > MAX_DNS_HOST_LENGTH || IPV4_PATTERN.matcher(host).matches()) {
            throw new IllegalArgumentException("Expected an exact DNS host name");
        }
        for (String label : host.split("[.]", -1)) {
            if (!DNS_LABEL_PATTERN.matcher(label).matches()) {
                throw new IllegalArgumentException(
                        "Hosts must be exact DNS names without schemes, ports or wildcards");
            }
        }
        return host;
    }

    public List<String> hosts(List<String> input) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("At least one host is required");
        }
        List<String> hosts = values(input).stream().map(this::host).toList();
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
        if (path.contains("%")) {
            validateEncodedPath(path);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Dot segments are not supported");
            }
        }
    }

    private static void validateEncodedPath(String path) {
        var decoded = new ByteArrayOutputStream();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c != '%') {
                decoded.write(c);
                continue;
            }
            if (i + 2 >= path.length() || !HEX_BYTE_PATTERN.matcher(path.substring(i + 1, i + 3)).matches()) {
                throw new IllegalArgumentException("Invalid encoded path");
            }
            int b = Integer.parseInt(path.substring(i + 1, i + 3), HEX_RADIX);
            i += 2;
            if (b < ASCII_BOUNDARY) {
                throw new IllegalArgumentException("Noncanonical encoded path");
            }
            decoded.write(b);
        }
        try {
            StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded.toByteArray()));
        } catch (CharacterCodingException ignored) {
            throw new IllegalArgumentException("Invalid UTF-8 path");
        }
    }
}
