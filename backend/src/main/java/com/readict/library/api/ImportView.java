package com.readict.library.api;

import java.time.Instant;
import java.util.UUID;

/** Import task projection: terminal states carry a safe, user-facing error. */
public record ImportView(UUID importId, UUID bookId, String state, String stage, int attempt,
                         Error error, Instant createdAt, Instant updatedAt, boolean reused) {
    public record Error(String code, String message, boolean retryable) {}
}
