# redis-distributed-lock

![CI](https://github.com/leeworms/redis-distributed-lock/actions/workflows/ci.yml/badge.svg)

실무에서 담당했던 매칭 서비스의 신청·픽 동시성 제어 경험을 재구성한 레포지토리입니다.
도메인 용어는 일부 일반화했고, 락 구조와 판단 과정은 실제와 동일합니다.

```bash
docker compose up --build
```

---

## 해결한 두 가지 동시성 문제

| 문제 | 상황 | 락 기준 | 실패 응답 |
|------|------|---------|-----------|
| 따닥 방지 | 멘티가 신청서 저장 버튼을 연속 클릭 | `memberNo` | `DUPLICATE_REQUEST` |
| 선착순 동시성 제어 | 여러 멘토가 같은 신청서를 동시에 픽 | `consultationId` | `RESOURCE_LOCKED` |

락을 하나로 합치지 않고 둘로 나눈 이유가 있습니다.
유저 기준 락만 있으면 서로 다른 멘토가 같은 신청서를 동시에 픽하는 걸 못 막고,
리소스 기준 락만 있으면 한 유저가 서로 다른 신청서를 연달아 저장하는 정상 요청까지 막힙니다.
보호하려는 대상이 다르면 락 키도 달라야 했습니다.

## 왜 Redis SETNX였나

검토했던 것들:

- **DB 유니크 제약** — 최후의 방어선으로는 유효하지만, 중복 "요청" 자체를 걸러주지는 못합니다. 매번 insert 시도 후 예외로 알게 되는 구조라 비용이 큽니다.
- **DB 비관적 락** — 커넥션을 물고 대기하는 시간이 늘어나고, 인스턴스가 여러 대라 락 경합이 DB로 몰립니다.
- **Redisson** — 재시도, 워치독 등 기능은 많지만, 여기서 필요한 건 "획득 실패하면 그냥 즉시 거절"이라 대기·재시도 기능 자체가 필요 없었습니다.

요구사항이 단순했기 때문에 `SET NX EX` 한 줄이면 충분하다고 판단했습니다.

```
SET LEEWORMS:USER_REQUEST_LOCK:CONSULTATION_FORM_SAVE:123  LOCKED  NX  EX 3
SET LEEWORMS:RESOURCE_LOCK:MATCHING_PICK:1000              LOCKED  NX  EX 10
```

| 옵션 | 역할 |
|------|------|
| `NX` | 키가 없을 때만 저장 → 락 획득을 원자적으로 처리 |
| `EX` | TTL 설정 → 프로세스 장애가 나도 락이 영구히 남지 않음 |

## 처리 흐름

![diagram.png](assets/diagram.png)

대기나 재시도는 없습니다.  
획득하면 실행, 못 하면 바로 거절, Redis가 죽어 있으면 락 없이 실행.  
이 3가지가 전부입니다.

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


## 락 획득 플로우

`LockManager` 내부에서 키를 만들고, Redis에 락을 시도하고, 작업을 실행합니다.

```java
// 1. 키 생성
String lockKey = lockKeyBuilder.userLockKey(lockPurpose, memberNo);
// → "LEEWORMS:USER_REQUEST_LOCK:CONSULTATION_FORM_SAVE:123"

// 2. 락 획득 시도
boolean acquired = tryLock(lockKey, ttl);
// → SET LEEWORMS:USER_REQUEST_LOCK:CONSULTATION_FORM_SAVE:123 LOCKED NX EX 3

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

## Fail-Open 판단

Redis 장애 시 요청을 막지 않고 비즈니스 로직을 그냥 실행합니다.

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

신청서 개수나 상태가 일부 어긋나는 문제는 운영에서 확인 후 보정할 수 있었고,
Redis 장애 때문에 신청 자체가 막히는 쪽이 더 큰 문제라고 판단했습니다.
락은 UX를 지키는 장치고, 최종 정합성은 DB 제약과 상태 검증이 책임지는 구조입니다.

물론 이 판단은 도메인에 따라 다릅니다. 결제나 재고처럼 중복이 곧 손실인 곳이었다면 반대로 갔을 겁니다.

## 실행

```bash
docker compose up --build
```

```bash
# 유저 락 — 같은 memberNo로 빠르게 두 번 요청 → 두 번째는 DUPLICATE_REQUEST
curl -X POST "localhost:8080/api/demo/form/consultation/copy?memberNo=123&formId=1000" &
curl -X POST "localhost:8080/api/demo/form/consultation/copy?memberNo=123&formId=1000"

# 리소스 락 — 같은 consultationId에 서로 다른 멘토가 동시 픽 → 한 명만 성공
curl -X POST "localhost:8080/api/demo/form/consultation/pick/1000?mentorNo=501" &
curl -X POST "localhost:8080/api/demo/form/consultation/pick/1000?mentorNo=502"

```

## 다루지 않은 것들

- **락 소유권 검증** : 작업이 TTL보다 오래 걸리면 락이 만료되고, 그 뒤의 해제가 다른 요청의 락을 지울 수 있습니다. 값에 요청 식별자를 넣고 Lua로 "내 락일 때만 삭제"하면 해결되지만, 여기서는 작업 시간(수백 ms) 대비 TTL이 충분해서 단순한 쪽을 택했습니다.
- **fail-open 모니터링** : 락 없이 우회된 요청이 얼마나 되는지 에러 로그로만 남겼습니다. 
