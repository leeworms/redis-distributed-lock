package com.leeworms.lock.core;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RedisLockClient {

    private final StringRedisTemplate redisTemplate;

    // 소유자 확인 후 삭제 — 두 단계를 원자적으로 처리하기 위해 Lua Script 사용.
    // GET 후 DELETE로 나누면 두 요청 사이에 다른 요청의 락을 삭제하는 레이스가 생긴다.
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    /**
     * SET NX EX로 락 획득. value에 호출자 고유 토큰을 저장해 release 시 소유권 검증에 사용한다.
     *
     * @return 획득 성공 시 토큰, 실패 시 null
     */
    public String tryAcquire(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    /**
     * 자신이 설정한 토큰인 경우에만 키를 삭제한다.
     * TTL 초과로 락이 이미 만료돼 다른 요청이 재획득한 경우, 그 락을 건드리지 않는다.
     */
    public void release(String key, String token) {
        redisTemplate.execute(RELEASE_SCRIPT, Collections.singletonList(key), token);
    }
}
