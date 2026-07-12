package com.leeworms.lock.core;

import com.leeworms.lock.exception.DuplicateRequestException;
import com.leeworms.lock.exception.ResourceLockedException;
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

// Testcontainers로 Redis 컨테이너를 직접 띄워 실제 Redis 명령 기준으로 검증합니다. (./gradlew test)
// - 같은 유저의 중복 요청 차단
// - 같은 리소스에 대한 동시 접근 차단, 다른 리소스 요청은 통과
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
    void executeWithLockBlocksDuplicateRequestForSameMember() throws Exception {
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            executor.submit(() -> lockManager.executeWithLock(LockPurpose.CONSULTATION_FORM_SAVE, 100L, Duration.ofSeconds(5), () -> {
                lockHeld.countDown();
                await(releaseLock);
                return "saved";
            }));

            assertThat(lockHeld.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> lockManager.executeWithLock(LockPurpose.CONSULTATION_FORM_SAVE, 100L, () -> "duplicate"))
                    .isInstanceOf(DuplicateRequestException.class);
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void executeWithResourceLockBlocksSameResourceOnly() throws Exception {
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

            assertThatThrownBy(() -> lockManager.executeWithResourceLock(LockPurpose.MATCHING_PICK, 200L, () -> "duplicate"))
                    .isInstanceOf(ResourceLockedException.class);

            String result = lockManager.executeWithResourceLock(LockPurpose.MATCHING_PICK, 201L, () -> "ok");
            assertThat(result).isEqualTo("ok");
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
