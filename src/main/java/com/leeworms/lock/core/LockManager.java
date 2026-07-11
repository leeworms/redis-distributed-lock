package com.leeworms.lock.core;

import com.leeworms.lock.exception.DuplicateRequestException;
import com.leeworms.lock.exception.ResourceLockedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class LockManager {

    private static final Duration DEFAULT_USER_LOCK_TTL = Duration.ofSeconds(3);
    private static final Duration DEFAULT_RESOURCE_LOCK_TTL = Duration.ofSeconds(10);

    private final RedisLockClient redisLockClient;
    private final LockKeyBuilder lockKeyBuilder;

    public <T> T executeWithLock(LockPurpose lockPurpose, Long memberNo, Supplier<T> task) {
        return executeWithLock(lockPurpose, memberNo, DEFAULT_USER_LOCK_TTL, task);
    }

    public <T> T executeWithLock(LockPurpose lockPurpose, Long memberNo, Duration ttl, Supplier<T> task) {
        String lockKey = lockKeyBuilder.userLockKey(lockPurpose, memberNo);
        boolean acquired = tryLock(lockKey, ttl);

        if (!acquired) {
            log.warn("중복 요청 감지 - lockPurpose: {}, memberNo: {}", lockPurpose, memberNo);
            throw new DuplicateRequestException("Duplicate request detected for user: " + memberNo);
        }

        try {
            return task.get();
        } finally {
            unlock(lockKey);
        }
    }

    public void executeWithLock(LockPurpose lockPurpose, Long memberNo, Runnable task) {
        executeWithLock(lockPurpose, memberNo, DEFAULT_USER_LOCK_TTL, task);
    }

    public void executeWithLock(LockPurpose lockPurpose, Long memberNo, Duration ttl, Runnable task) {
        executeWithLock(lockPurpose, memberNo, ttl, () -> {
            task.run();
            return null;
        });
    }

    public <T> T executeWithResourceLock(LockPurpose lockPurpose, Long resourceId, Supplier<T> task) {
        return executeWithResourceLock(lockPurpose, resourceId, DEFAULT_RESOURCE_LOCK_TTL, task);
    }

    public <T> T executeWithResourceLock(LockPurpose lockPurpose, Long resourceId, Duration ttl, Supplier<T> task) {
        String lockKey = lockKeyBuilder.resourceLockKey(lockPurpose, resourceId);
        boolean acquired = tryLock(lockKey, ttl);

        if (!acquired) {
            log.warn("리소스 락 획득 실패 - lockPurpose: {}, resourceId: {}", lockPurpose, resourceId);
            throw new ResourceLockedException("Resource is currently locked: " + lockPurpose + "/" + resourceId);
        }

        try {
            return task.get();
        } finally {
            unlock(lockKey);
        }
    }

    public void executeWithResourceLock(LockPurpose lockPurpose, Long resourceId, Runnable task) {
        executeWithResourceLock(lockPurpose, resourceId, DEFAULT_RESOURCE_LOCK_TTL, task);
    }

    public void executeWithResourceLock(LockPurpose lockPurpose, Long resourceId, Duration ttl, Runnable task) {
        executeWithResourceLock(lockPurpose, resourceId, ttl, () -> {
            task.run();
            return null;
        });
    }

    private boolean tryLock(String key, Duration ttl) {
        try {
            return redisLockClient.tryAcquire(key, ttl);
        } catch (Exception e) {
            log.error("Redis 락 획득 중 오류 발생 - key: {}", key, e);
            return true;
        }
    }

    private void unlock(String key) {
        try {
            redisLockClient.release(key);
        } catch (Exception e) {
            log.error("Redis 락 해제 중 오류 발생 - key: {}", key, e);
        }
    }
}
