package com.mgm.attachmenteditor.webdav;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, single-instance lock table keyed by file name. Good enough for
 * this concept (one server instance, a handful of files); a real deployment
 * would need a shared store if the server is scaled out.
 */
class LockManager {

    record LockInfo(String token, String owner, Instant expiresAt) {
        boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private final Map<String, LockInfo> locksByFile = new ConcurrentHashMap<>();

    LockInfo lock(String filename, String owner, Duration timeout) {
        LockInfo info = new LockInfo("opaquelocktoken:" + UUID.randomUUID(), owner, Instant.now().plus(timeout));
        locksByFile.put(filename, info);
        return info;
    }

    Optional<LockInfo> refresh(String filename, String token, Duration timeout) {
        LockInfo current = activeLock(filename);
        if (current == null || !current.token().equals(token)) {
            return Optional.empty();
        }
        LockInfo renewed = new LockInfo(current.token(), current.owner(), Instant.now().plus(timeout));
        locksByFile.put(filename, renewed);
        return Optional.of(renewed);
    }

    LockInfo activeLock(String filename) {
        LockInfo info = locksByFile.get(filename);
        if (info != null && info.expired()) {
            locksByFile.remove(filename, info);
            return null;
        }
        return info;
    }

    boolean isLockedByOther(String filename, String presentedToken) {
        LockInfo info = activeLock(filename);
        return info != null && !info.token().equals(presentedToken);
    }

    void unlock(String filename, String token) {
        locksByFile.computeIfPresent(filename, (k, v) -> v.token().equals(token) ? null : v);
    }
}
