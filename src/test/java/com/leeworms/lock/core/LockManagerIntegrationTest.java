package com.leeworms.lock.core;

import com.leeworms.lock.exception.DuplicateRequestException;
import com.leeworms.lock.exception.ResourceLockedException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LockManager 통합 테스트.
 *
 * <p>Testcontainers로 Redis 컨테이너를 직접 띄워 실제 Redis 명령 기준으로 검증합니다. (./gradlew test)
 * <ul>
 *   <li>같은 유저의 중복 요청 차단 (유저 락)</li>
 *   <li>같은 리소스에 대한 동시 접근 차단, 다른 리소스 요청은 통과 (리소스 락)</li>
 * </ul>
 *
 * <p>락을 "점유 중인 상태"를 재현하기 위해 CountDownLatch 두 개를 조합합니다.
 * <ul>
 *   <li>{@code lockHeld}  : 백그라운드 스레드가 락을 획득했음을 메인 스레드에 알립니다.</li>
 *   <li>{@code releaseLock}: 메인 스레드가 검증을 마친 뒤 백그라운드 스레드를 해제합니다.</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest
class LockManagerIntegrationTest {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @Autowired
    private LockManager lockManager;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Test
    @DisplayName("같은 유저가 동일 목적으로 중복 요청하면 DuplicateRequestException이 발생한다")
    void 같은_유저의_중복_요청은_DuplicateRequestException을_던진다() throws Exception {
        // given: 백그라운드 스레드가 memberNo=100L 락을 점유한 채 대기
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            executor.submit(() -> lockManager.executeWithLock(LockPurpose.CONSULTATION_FORM_SAVE, 100L, Duration.ofSeconds(5), () -> {
                lockHeld.countDown();   // 락 획득 완료 신호
                await(releaseLock);     // 메인 스레드 검증 완료 후 해제 대기
                return "saved";
            }));
            // 경쟁 조건 방지 — 락이 실제로 잡힌 뒤에 중복 요청을 보내야 함
            assertThat(lockHeld.await(2, TimeUnit.SECONDS)).isTrue();

            // when: 동일 유저(100L)·동일 목적으로 재요청
            // then: DuplicateRequestException 발생
            assertThatThrownBy(() -> lockManager.executeWithLock(LockPurpose.CONSULTATION_FORM_SAVE, 100L, () -> "duplicate"))
                    .isInstanceOf(DuplicateRequestException.class);
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("같은 리소스에 대한 동시 요청은 차단되고, 다른 리소스 요청은 통과한다")
    void 동일_리소스_잠금_중_같은_리소스는_차단되고_다른_리소스는_통과한다() throws Exception {
        // given: 백그라운드 스레드가 resourceId=200L 락을 점유한 채 대기
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            executor.submit(() -> lockManager.executeWithResourceLock(LockPurpose.MATCHING_PICK, 200L, Duration.ofSeconds(5), () -> {
                lockHeld.countDown();
                await(releaseLock);
                return "picked";
            }));
            assertThat(lockHeld.await(2, TimeUnit.SECONDS)).isTrue();

            // when: 동일 리소스(200L) 재요청
            // then: ResourceLockedException 발생
            assertThatThrownBy(() -> lockManager.executeWithResourceLock(LockPurpose.MATCHING_PICK, 200L, () -> "duplicate"))
                    .isInstanceOf(ResourceLockedException.class);

            // when: 다른 리소스(201L) 요청
            // then: 리소스 락은 키 단위이므로 독립적으로 통과
            String result = lockManager.executeWithResourceLock(LockPurpose.MATCHING_PICK, 201L, () -> "ok");
            assertThat(result).isEqualTo("ok");
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    // InterruptedException을 체크 예외로 전파하지 않기 위한 래퍼 — 람다 내부에서 사용
    private static void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
