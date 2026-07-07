package com.samato.restaurantservice.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Cache config: Redis-backed cache for {@code restaurants} cache.
 *
 * Why Redis for a cache (vs. Caffeine in-memory)?
 *   - Multiple instances of restaurant-service share the same cache.
 *   - Hot reads (single restaurant by id) are the bread and butter of
 *     a delivery app. You want them sub-millisecond and shared.
 *
 * Configuration choices:
 *   - TTL = 5 minutes — long enough to absorb most reads, short enough
 *     to recover from a stale write quickly.
 *   - JSON value serialization — debuggable, language-agnostic.
 *   - String key serialization — readable in redis-cli.
 *   - Cache nulls NOT cached — a missing restaurant should not stick.
 */
@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory cf) {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        var jsonSerializer = new GenericJackson2JsonRedisSerializer(om);

        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(jsonSerializer))
                .prefixCacheNameWith("samato:cache:");

        return RedisCacheManager.builder(cf)
                .cacheDefaults(base)
                .build();
    }
}
