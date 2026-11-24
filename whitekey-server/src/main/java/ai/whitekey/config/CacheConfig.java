package ai.whitekey.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
@EnableCaching(order = Ordered.LOWEST_PRECEDENCE - 1)
public class CacheConfig {

    public static final String HTTP_RULES = "httpRules";
    public static final String HTTP_INJECTIONS = "httpInjections";

    @Bean
    public CacheManager cacheManager() {
        return new TransactionAwareCacheManagerProxy(
                new ConcurrentMapCacheManager(HTTP_RULES, HTTP_INJECTIONS));
    }
}
