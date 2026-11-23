package com.readict.library.infrastructure.persistence.entity;

import java.time.Instant;
import java.util.UUID;
import com.baomidou.mybatisplus.annotation.*;
import com.readict.shared.persistence.typehandler.UtcInstantTypeHandler;

@TableName(value = "import_task", autoResultMap = true)
public class ImportTaskEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID userId;
    private UUID bookId;
    private String state;
    private String stage;
    private int attempt;
    @TableField(typeHandler = UtcInstantTypeHandler.class)
    private Instant nextAttemptAt;
    private UUID leaseToken;
    @TableField(typeHandler = UtcInstantTypeHandler.class)
    private Instant leaseUntil;
    private UUID requestId;
    private byte[] requestHash;
    private String errorCode;
    private String safeErrorMessage;
    private boolean cleanupPending;
    @TableField(typeHandler = UtcInstantTypeHandler.class)
    private Instant createdAt;
    @TableField(typeHandler = UtcInstantTypeHandler.class)
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    public UUID getBookId() { return bookId; }
    public void setBookId(UUID value) { bookId = value; }
    public String getState() { return state; }
    public void setState(String value) { state = value; }
    public String getStage() { return stage; }
    public void setStage(String value) { stage = value; }
    public int getAttempt() { return attempt; }
    public void setAttempt(int value) { attempt = value; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant value) { nextAttemptAt = value; }
    public UUID getLeaseToken() { return leaseToken; }
    public void setLeaseToken(UUID value) { leaseToken = value; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant value) { leaseUntil = value; }
    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID value) { requestId = value; }
    public byte[] getRequestHash() { return requestHash; }
    public void setRequestHash(byte[] value) { requestHash = value; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode = value; }
    public String getSafeErrorMessage() { return safeErrorMessage; }
    public void setSafeErrorMessage(String value) { safeErrorMessage = value; }
    public boolean isCleanupPending() { return cleanupPending; }
    public void setCleanupPending(boolean value) { cleanupPending = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
}
