package com.leeworms.lock.core;

import com.leeworms.lock.exception.DuplicateRequestException;
import com.leeworms.lock.exception.ResourceLockedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class LockManager {

    private final RedisLockClient redisLockClient;
    private final LockKeyBuilder lockKeyBuilder;

    public <T> T executeWithLock(LockPurpose lockPurpose, Long memberNo, Supplier<T> task) {
        String lockKey = lockKeyBuilder.userLockKey(lockPurpose, memberNo);
        String token = tryLock(lockKey, lockPurpose);

        if (token == null) {
            log.warn("중복 요청 감지 - lockPurpose: {}, memberNo: {}", lockPurpose, memberNo);
            throw new DuplicateRequestException("Duplicate request detected for user: " + memberNo);
        }

        try {
            return task.get();
        } finally {
            unlock(lockKey, token);
        }
    }

    public void executeWithLock(LockPurpose lockPurpose, Long memberNo, Runnable task) {
        executeWithLock(lockPurpose, memberNo, () -> {
            task.run();
            return null;
        });
    }

    public <T> T executeWithResourceLock(LockPurpose lockPurpose, Long resourceId, Supplier<T> task) {
        String lockKey = lockKeyBuilder.resourceLockKey(lockPurpose, resourceId);
        String token = tryLock(lockKey, lockPurpose);

        if (token == null) {
            log.warn("리소스 락 획득 실패 - lockPurpose: {}, resourceId: {}", lockPurpose, resourceId);
            throw new ResourceLockedException("Resource is currently locked: " + lockPurpose + "/" + resourceId);
        }

        try {
            return task.get();
        } finally {
            unlock(lockKey, token);
        }
    }

    public void executeWithResourceLock(LockPurpose lockPurpose, Long resourceId, Runnable task) {
        executeWithResourceLock(lockPurpose, resourceId, () -> {
            task.run();
            return null;
        });
    }

    // Fail-Open: Redis 장애 시 null 대신 고정 토큰 반환해 비즈니스 로직을 진행시킴.
    // 극소수의 중복 처리 가능성을 감수하고 서비스 가용성을 우선한 정책.
    private String tryLock(String key, LockPurpose purpose) {
        try {
            return redisLockClient.tryAcquire(key, purpose.getTtl());
        } catch (Exception e) {
            log.error("Redis 락 획득 중 오류 발생 (Fail-Open 적용) - key: {}", key, e);
            return "FAIL_OPEN";
        }
    }

    private void unlock(String key, String token) {
        try {
            redisLockClient.release(key, token);
        } catch (Exception e) {
            log.error("Redis 락 해제 중 오류 발생 - key: {}", key, e);
        }
    }
}
