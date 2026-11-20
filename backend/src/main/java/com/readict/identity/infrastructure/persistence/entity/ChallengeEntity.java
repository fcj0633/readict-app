package com.readict.identity.infrastructure.persistence.entity;

import java.util.UUID;
import java.time.Instant;
import com.baomidou.mybatisplus.annotation.*;
import com.readict.shared.persistence.typehandler.*;

@TableName(value="verification_challenge", autoResultMap=true)
public class ChallengeEntity {
    @TableId(type=IdType.INPUT)
    private UUID id;
    @TableField(typeHandler=UuidBinaryTypeHandler.class)
    private UUID userId;
    private String purpose;
    private String identifierType;
    private byte[] targetKeyHmac;
    private String deliveryMode;
    private byte[] codeMac;
    private int macKeyVersion;
    private String state;
    private int generation;
    private int attempts;
    private int maxAttempts;
    private Long authVersion;
    @TableField(typeHandler=UtcInstantTypeHandler.class)
    private Instant createdAt;
    @TableField(typeHandler=UtcInstantTypeHandler.class)
    private Instant expiresAt;
    @TableField(typeHandler=UtcInstantTypeHandler.class)
    private Instant nextSendAt;
    @TableField(typeHandler=UtcInstantTypeHandler.class)
    private Instant verifiedAt;
    @TableField(typeHandler=UtcInstantTypeHandler.class)
    private Instant consumedAt;
    public UUID getId() { return id; }
    public void setId(UUID value) { id=value; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId=value; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String value) { purpose=value; }
    public String getIdentifierType() { return identifierType; }
    public void setIdentifierType(String value) { identifierType=value; }
    public byte[] getTargetKeyHmac() { return targetKeyHmac; }
    public void setTargetKeyHmac(byte[] value) { targetKeyHmac=value; }
    public String getDeliveryMode() { return deliveryMode; }
    public void setDeliveryMode(String value) { deliveryMode=value; }
    public byte[] getCodeMac() { return codeMac; }
    public void setCodeMac(byte[] value) { codeMac=value; }
    public int getMacKeyVersion() { return macKeyVersion; }
    public void setMacKeyVersion(int value) { macKeyVersion=value; }
    public String getState() { return state; }
    public void setState(String value) { state=value; }
    public int getGeneration() { return generation; }
    public void setGeneration(int value) { generation=value; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int value) { attempts=value; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int value) { maxAttempts=value; }
    public Long getAuthVersion() { return authVersion; }
    public void setAuthVersion(Long value) { authVersion=value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt=value; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant value) { expiresAt=value; }
    public Instant getNextSendAt() { return nextSendAt; }
    public void setNextSendAt(Instant value) { nextSendAt=value; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant value) { verifiedAt=value; }
    public Instant getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Instant value) { consumedAt=value; }
}
