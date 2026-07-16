package com.leeworms.lock.core;

import org.junit.jupiter.api.DisplayName;
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

/**
 * RedisLockClient 통합 테스트.
 *
 * <p>Testcontainers로 띄운 Redis에 직접 연결하여 락의 원자성과 TTL 만료 동작을 검증합니다.
 * <ul>
 *   <li>동일 키 중복 획득 시 false 반환</li>
 *   <li>TTL 만료 후 동일 키 재획득 가능</li>
 * </ul>
 */
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
    @DisplayName("이미 잠긴 키에 대한 재획득 시도는 false를 반환한다")
    void 이미_잠긴_키_재획득_시도는_false를_반환한다() {
        // given: 동일 키를 이미 획득한 상태
        String key = "test:lock:duplicate";
        boolean first = lockClient.tryAcquire(key, Duration.ofSeconds(5));

        // when: 같은 키로 재획득 시도
        // Redis SET NX는 키가 이미 존재하면 아무것도 하지 않고 null을 반환
        boolean second = lockClient.tryAcquire(key, Duration.ofSeconds(5));

        // then
        assertThat(first).isTrue();
        assertThat(second).isFalse();

        lockClient.release(key);
    }

    @Test
    @DisplayName("TTL이 만료되면 동일 키를 다시 획득할 수 있다")
    void TTL_만료_후_동일_키_재획득에_성공한다() throws InterruptedException {
        // given: 동일 키를 TTL 1초로 획득
        String key = "test:lock:ttl";
        assertThat(lockClient.tryAcquire(key, Duration.ofSeconds(1))).isTrue();

        // when: TTL 만료 대기 (200ms 여유 — 타이밍 지터로 인한 플레이키 테스트 방지)
        Thread.sleep(1200);

        // then: 만료된 키를 다시 획득할 수 있어야 함
        assertThat(lockClient.tryAcquire(key, Duration.ofSeconds(1))).isTrue();
        lockClient.release(key);
    }
}
