package ai.whitekey.config;

import ai.whitekey.policy.HttpPolicyValidator;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "app.proxy")
public record ProxyProperties(
        boolean enabled,
        int port,
        String address,
        Duration connectTimeout,
        Duration shutdownTimeout,
        DataSize maxInitialLineLength,
        DataSize maxHeaderSize,
        DataSize maxChunkSize,
        String caCertFile,
        String caKeyFile,
        List<String> tlsPassthroughHosts) {

    public ProxyProperties {
        connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(15);
        shutdownTimeout = shutdownTimeout != null ? shutdownTimeout : Duration.ofSeconds(2);
        maxInitialLineLength = maxInitialLineLength != null ? maxInitialLineLength : DataSize.ofKilobytes(8);
        maxHeaderSize = maxHeaderSize != null ? maxHeaderSize : DataSize.ofKilobytes(32);
        maxChunkSize = maxChunkSize != null ? maxChunkSize : DataSize.ofKilobytes(16);
        caCertFile = (caCertFile != null && !caCertFile.isBlank()) ? caCertFile.trim() : "certs/whitekey_ca.crt";
        caKeyFile = (caKeyFile != null && !caKeyFile.isBlank()) ? caKeyFile.trim() : "whitekey_ca.key";
        tlsPassthroughHosts = tlsPassthroughHosts == null
                ? List.of()
                : tlsPassthroughHosts.stream()
                        .filter(host -> host != null && !host.isBlank())
                        .map(HttpPolicyValidator::normalizeHostIfValid)
                        .toList();
    }

    public boolean isTlsPassthroughHost(String host) {
        if (host == null || host.isBlank() || tlsPassthroughHosts.isEmpty()) {
            return false;
        }
        String normalized = HttpPolicyValidator.normalizeHostIfValid(host);
        boolean isIp = HttpPolicyValidator.isIpAddress(normalized);
        for (String pattern : tlsPassthroughHosts) {
            if (pattern.equals(normalized)) {
                return true;
            }
            if (!isIp && pattern.startsWith("*.") && normalized.endsWith(pattern.substring(1))
                    && normalized.length() > pattern.length() - 1) {
                return true;
            }
        }
        return false;
    }
}
