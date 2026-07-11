package com.leeworms.lock.core;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class RedisLockClient {

    private final StringRedisTemplate redisTemplate;

    public boolean tryAcquire(String key, Duration ttl) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "LOCKED", ttl);
        return Boolean.TRUE.equals(acquired);
    }

    public void release(String key) {
        redisTemplate.delete(key);
    }
}
