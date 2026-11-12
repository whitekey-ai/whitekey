package ai.whitekey.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.proxy")
public record ProxyProperties(boolean enabled, int port, String address) {}
