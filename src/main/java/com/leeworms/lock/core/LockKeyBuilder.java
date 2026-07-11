package com.leeworms.lock.core;

import org.springframework.stereotype.Component;

@Component
public class LockKeyBuilder {

    private static final String USER_KEY_PREFIX = "RESEARCH:USER_REQUEST_LOCK:";
    private static final String RESOURCE_KEY_PREFIX = "RESEARCH:RESOURCE_LOCK:";

    public String userLockKey(LockPurpose lockPurpose, Long memberNo) {
        return this.concat(USER_KEY_PREFIX, lockPurpose.name(), String.valueOf(memberNo));
    }

    public String resourceLockKey(LockPurpose lockPurpose, Long resourceId) {
        return this.concat(RESOURCE_KEY_PREFIX, lockPurpose.name(), String.valueOf(resourceId));
    }

    private String concat(String... args) {
        return String.join(":", args);
    }
}
