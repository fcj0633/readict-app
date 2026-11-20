package com.readict.identity.domain;

import java.io.Serializable;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

public record ReadictPrincipal(UUID userId, long authVersion, Instant authenticatedAt) implements Principal, Serializable {
    private static final long serialVersionUID=1L;
    @Override public String getName() { return userId.toString(); }
}
