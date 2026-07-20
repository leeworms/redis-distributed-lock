package com.leeworms.lock.core;

import java.time.Duration;

public enum LockPurpose {

    // 유저 단위 락: 같은 회원의 중복 제출 방지. 신청서 처리는 수 초 이내 완료.
    CONSULTATION_FORM_SAVE(Duration.ofSeconds(3)),

    // 리소스 단위 락: 여러 멘토의 동시 픽 경쟁. 선착순 확정까지 최대 10초 허용.
    MATCHING_PICK(Duration.ofSeconds(10));

    private final Duration ttl;

    LockPurpose(Duration ttl) {
        this.ttl = ttl;
    }

    public Duration getTtl() {
        return ttl;
    }
}
