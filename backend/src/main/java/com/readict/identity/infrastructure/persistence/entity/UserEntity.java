package com.readict.identity.infrastructure.persistence.entity;

import java.util.UUID;
import java.time.Instant;
import com.baomidou.mybatisplus.annotation.*;
import com.readict.shared.persistence.typehandler.*;

@TableName(value="app_user", autoResultMap=true)
public class UserEntity {
    @TableId(type=IdType.INPUT)
    private UUID id;
    private String email;
    private String emailKey;
    private String phoneE164;
    private String passwordHash;
    private boolean emailVerified;
    private boolean phoneVerified;
    private String status;
    private long authVersion;
    @TableField(typeHandler=UtcInstantTypeHandler.class)
    private Instant createdAt;
    @TableField(typeHandler=UtcInstantTypeHandler.class)
    private Instant updatedAt;
    public UUID getId() { return id; }
    public void setId(UUID value) { id=value; }
    public String getEmail() { return email; }
    public void setEmail(String value) { email=value; }
    public String getEmailKey() { return emailKey; }
    public void setEmailKey(String value) { emailKey=value; }
    public String getPhoneE164() { return phoneE164; }
    public void setPhoneE164(String value) { phoneE164=value; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String value) { passwordHash=value; }
    public boolean getEmailVerified() { return emailVerified; }
    public void setEmailVerified(boolean value) { emailVerified=value; }
    public boolean getPhoneVerified() { return phoneVerified; }
    public void setPhoneVerified(boolean value) { phoneVerified=value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status=value; }
    public long getAuthVersion() { return authVersion; }
    public void setAuthVersion(long value) { authVersion=value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt=value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt=value; }
}
