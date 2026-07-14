# redis-distributed-lock-kit

실무에서 담당했던 **매칭 서비스의 신청·픽 동시성 제어** 경험을 재구성한 레포지토리입니다.

---

## 해결한 두 가지 동시성 문제

| 문제 | 상황 | 락 기준 | 실패 응답 |
|------|------|---------|-----------|
| **따닥 방지** | 멘티가 신청서 저장 버튼을 연속 클릭 | `memberNo` | `DUPLICATE_REQUEST` |
| **선착순 동시성 제어** | 여러 멘토가 같은 신청서를 동시에 픽 | `consultationId` | `RESOURCE_LOCKED` |

- Redis가 죽으면 락을 건너뛰고 비즈니스 로직을 그냥 실행합니다 **(fail-open)**

---

## 프로젝트 구조

```
src/main/java/com/leeworms/lock
├── controller
│   ├── LockDemoController.java     # 유저 락 / 리소스 락 사용 예시
│   └── GlobalExceptionHandler.java
├── core
│   ├── LockManager.java            # 애플리케이션 코드의 진입점
│   ├── RedisLockClient.java        # Redis SET NX EX 명령 담당
│   ├── LockKeyBuilder.java         # 락 키 규칙을 한 곳에 집중
│   └── LockPurpose.java            # 락 목적 열거형
└── exception
    ├── DuplicateRequestException.java
    └── ResourceLockedException.java
```

---

## 사용 방법

컨트롤러에서 `LockManager`를 직접 호출합니다.

```java
// 유저 락 — 같은 memberNo로 들어오는 중복 요청 차단
lockManager.executeWithLock(LockPurpose.CONSULTATION_FORM_SAVE, memberNo, () -> {
    simulateWork(500);
    return "copied";
});

// 리소스 락 — 같은 consultationId에 대한 선착순 경쟁 제어
lockManager.executeWithResourceLock(LockPurpose.MATCHING_PICK, consultationId, () -> {
    simulateWork(300);
    return "picked";
});
```

---

## 락 획득 플로우

`LockManager` 내부에서 키를 만들고, Redis에 락을 시도하고, 작업을 실행합니다.

```java
// 1. 키 생성
String lockKey = lockKeyBuilder.userLockKey(lockPurpose, memberNo);
// → "RESEARCH:USER_REQUEST_LOCK:CONSULTATION_FORM_SAVE:123"

// 2. 락 획득 시도
boolean acquired = tryLock(lockKey, ttl);
// → SET RESEARCH:USER_REQUEST_LOCK:CONSULTATION_FORM_SAVE:123 LOCKED NX EX 3

// 3. 락 획득 실패 시 즉시 예외
if (!acquired) {
    throw new DuplicateRequestException(...);
}

// 4. 작업 실행 후 락 해제
try {
    return task.get();
} finally {
    unlock(lockKey);
}
```

---

## 락 키 설계

```
SET RESEARCH:USER_REQUEST_LOCK:CONSULTATION_FORM_SAVE:123  LOCKED  NX  EX 3
SET RESEARCH:RESOURCE_LOCK:MATCHING_PICK:1000              LOCKED  NX  EX 10
```

| 옵션 | 역할 |
|------|------|
| `NX` | 키가 없을 때만 저장 → 락 획득을 원자적으로 처리 |
| `EX` | TTL 설정 → 프로세스 장애가 나도 락이 영구히 남지 않음 |

---

## Fail-Open 판단

Redis 장애 시 요청을 막지 않고 비즈니스 로직을 그냥 실행합니다.

DB 레벨의 유니크 제약이나 상태 검증이 최종 방어선을 잡아주고 있었고, Redis 장애 상황에서 신청 자체가 안 되는 경험이 더 나쁘다고 판단했습니다.

```java
private boolean tryLock(String key, Duration ttl) {
    try {
        return redisLockClient.tryAcquire(key, ttl);
    } catch (Exception e) {
        log.error("Redis 락 획득 중 오류 발생 - key: {}", key, e);
        return true; // fail-open: 락 실패를 성공으로 처리
    }
}
```

---

## 실행

```bash
docker compose up --build
```

```bash
# 유저 락 테스트 — 같은 memberNo로 빠르게 두 번 요청
curl -X POST "http://localhost:8080/api/demo/form/consultation/copy?memberNo=123&formId=1000"

# 리소스 락 테스트 — 같은 consultationId로 동시에 요청
curl -X POST "http://localhost:8080/api/demo/form/consultation/pick/1000?mentorNo=501"
```
