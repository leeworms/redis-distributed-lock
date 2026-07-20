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
 *   <li>동일 키 중복 획득 시 null 반환 (토큰 기반)</li>
 *   <li>TTL 만료 후 동일 키 재획득 가능</li>
 *   <li>Lua 스크립트로 소유자 토큰 불일치 시 삭제 거부</li>
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
    @DisplayName("이미 잠긴 키에 대한 재획득 시도는 null을 반환한다")
    void 이미_잠긴_키_재획득_시도는_null을_반환한다() {
        // given: 동일 키를 이미 획득한 상태
        String key = "test:lock:duplicate";
        String firstToken = lockClient.tryAcquire(key, Duration.ofSeconds(5));

        // when: 같은 키로 재획득 시도
        // Redis SET NX는 키가 이미 존재하면 아무것도 하지 않고 null을 반환
        String secondToken = lockClient.tryAcquire(key, Duration.ofSeconds(5));

        // then
        assertThat(firstToken).isNotNull();
        assertThat(secondToken).isNull();

        lockClient.release(key, firstToken);
    }

    @Test
    @DisplayName("TTL이 만료되면 동일 키를 다시 획득할 수 있다")
    void TTL_만료_후_동일_키_재획득에_성공한다() throws InterruptedException {
        // given: 동일 키를 TTL 1초로 획득
        String key = "test:lock:ttl";
        String firstToken = lockClient.tryAcquire(key, Duration.ofSeconds(1));
        assertThat(firstToken).isNotNull();

        // when: TTL 만료 대기 (200ms 여유 — 타이밍 지터로 인한 플레이키 테스트 방지)
        Thread.sleep(1200);

        // then: 만료된 키를 다시 획득할 수 있어야 함
        String secondToken = lockClient.tryAcquire(key, Duration.ofSeconds(1));
        assertThat(secondToken).isNotNull();
        lockClient.release(key, secondToken);
    }

    @Test
    @DisplayName("소유자가 다른 토큰으로 release를 시도하면 락이 삭제되지 않는다")
    void 다른_토큰으로_release_시도_시_락이_유지된다() {
        // given: A가 락을 획득
        String key = "test:lock:ownership";
        String tokenA = lockClient.tryAcquire(key, Duration.ofSeconds(5));
        assertThat(tokenA).isNotNull();

        // when: B가 다른 토큰으로 release 시도 (Lua 스크립트가 소유권 불일치를 감지해야 함)
        lockClient.release(key, "wrong-token");

        // then: 락이 여전히 존재하므로 재획득 시도는 실패해야 함
        String duplicateToken = lockClient.tryAcquire(key, Duration.ofSeconds(5));
        assertThat(duplicateToken).isNull();

        // cleanup
        lockClient.release(key, tokenA);
    }
}
