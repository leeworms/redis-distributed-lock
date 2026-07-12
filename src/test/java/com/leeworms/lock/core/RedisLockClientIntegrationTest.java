package com.leeworms.lock.core;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

// - 동일 키 중복 획득 실패
// - TTL 만료 후 재획득
@Testcontainers
@SpringBootTest
class RedisLockClientIntegrationTest {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @Autowired
    private RedisLockClient lockClient;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Test
    void tryAcquireReturnsFalseWhenKeyAlreadyLocked() {
        String key = "test:lock:duplicate";

        boolean first = lockClient.tryAcquire(key, Duration.ofSeconds(5));
        boolean second = lockClient.tryAcquire(key, Duration.ofSeconds(5));

        assertThat(first).isTrue();
        assertThat(second).isFalse();

        lockClient.release(key);
    }

    @Test
    void lockExpiresAfterTtl() throws InterruptedException {
        String key = "test:lock:ttl";

        assertThat(lockClient.tryAcquire(key, Duration.ofSeconds(1))).isTrue();

        Thread.sleep(1200);

        assertThat(lockClient.tryAcquire(key, Duration.ofSeconds(1))).isTrue();
        lockClient.release(key);
    }
}
