package ai.whitekey.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.proxy")
public record ProxyProperties(
        boolean enabled,
        int port,
        String address,
        Duration connectTimeout,
        Duration approvalPollInterval,
        Duration shutdownTimeout,
        DataSize streamBufferSize,
        DataSize maxInitialLineLength,
        DataSize maxHeaderSize,
        DataSize maxChunkSize) {

    public ProxyProperties {
        connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(15);
        approvalPollInterval = approvalPollInterval != null ? approvalPollInterval : Duration.ofSeconds(2);
        shutdownTimeout = shutdownTimeout != null ? shutdownTimeout : Duration.ofSeconds(2);
        streamBufferSize = streamBufferSize != null ? streamBufferSize : DataSize.ofKilobytes(64);
        maxInitialLineLength = maxInitialLineLength != null ? maxInitialLineLength : DataSize.ofKilobytes(8);
        maxHeaderSize = maxHeaderSize != null ? maxHeaderSize : DataSize.ofKilobytes(32);
        maxChunkSize = maxChunkSize != null ? maxChunkSize : DataSize.ofKilobytes(16);
    }
}
