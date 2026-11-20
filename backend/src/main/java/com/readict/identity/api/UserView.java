package com.readict.identity.api;

import java.util.UUID;
import com.readict.identity.infrastructure.persistence.entity.UserEntity;

public record UserView(UUID id, String email, String phone, boolean emailVerified, boolean phoneVerified) {
    public static UserView from(UserEntity u) { return new UserView(u.getId(),u.getEmail(),u.getPhoneE164(),u.getEmailVerified(),u.getPhoneVerified()); }
}
