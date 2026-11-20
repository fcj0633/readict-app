package com.readict.identity.infrastructure.persistence.entity;

import java.util.UUID;
import java.time.Instant;
import com.baomidou.mybatisplus.annotation.*;
import com.readict.shared.persistence.typehandler.*;

@TableName(value="password_reset_credential", autoResultMap=true)
public class ResetCredentialEntity {
    @TableId(type=IdType.INPUT)
    private UUID id;
    @TableField(typeHandler=UuidBinaryTypeHandler.class)
    private UUID userId;
    @TableField(typeHandler=UuidBinaryTypeHandler.class)
    private UUID challengeId;
    private byte[] tokenHash;
    private long authVersion;
    private String proofMode;
    @TableField(typeHandler=UtcInstantTypeHandler.class)
    private Instant createdAt;
    @TableField(typeHandler=UtcInstantTypeHandler.class)
    private Instant expiresAt;
    @TableField(typeHandler=UtcInstantTypeHandler.class)
    private Instant consumedAt;
    public UUID getId() { return id; }
    public void setId(UUID value) { id=value; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId=value; }
    public UUID getChallengeId() { return challengeId; }
    public void setChallengeId(UUID value) { challengeId=value; }
    public byte[] getTokenHash() { return tokenHash; }
    public void setTokenHash(byte[] value) { tokenHash=value; }
    public long getAuthVersion() { return authVersion; }
    public void setAuthVersion(long value) { authVersion=value; }
    public String getProofMode() { return proofMode; }
    public void setProofMode(String value) { proofMode=value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt=value; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant value) { expiresAt=value; }
    public Instant getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Instant value) { consumedAt=value; }
}
