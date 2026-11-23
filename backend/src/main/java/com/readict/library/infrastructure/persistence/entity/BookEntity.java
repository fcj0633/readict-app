package com.readict.library.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import com.baomidou.mybatisplus.annotation.*;
import com.readict.shared.persistence.typehandler.UtcInstantTypeHandler;

@TableName(value = "book", autoResultMap = true)
public class BookEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID userId;
    private UUID originKey;
    private byte[] sourceHash;
    private long sourceSize;
    private String title;
    private String author;
    private String originalFilename;
    private String status;
    private UUID activeArtifactId;
    @TableField(typeHandler = UtcInstantTypeHandler.class)
    private Instant lastReadAt;
    @TableField(typeHandler = UtcInstantTypeHandler.class)
    private Instant createdAt;
    @TableField(typeHandler = UtcInstantTypeHandler.class)
    private Instant updatedAt;
    @TableField(exist = false)
    private BigDecimal progressRatio;

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    public UUID getOriginKey() { return originKey; }
    public void setOriginKey(UUID value) { originKey = value; }
    public byte[] getSourceHash() { return sourceHash; }
    public void setSourceHash(byte[] value) { sourceHash = value; }
    public long getSourceSize() { return sourceSize; }
    public void setSourceSize(long value) { sourceSize = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getAuthor() { return author; }
    public void setAuthor(String value) { author = value; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String value) { originalFilename = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public UUID getActiveArtifactId() { return activeArtifactId; }
    public void setActiveArtifactId(UUID value) { activeArtifactId = value; }
    public Instant getLastReadAt() { return lastReadAt; }
    public void setLastReadAt(Instant value) { lastReadAt = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
    public BigDecimal getProgressRatio() { return progressRatio; }
    public void setProgressRatio(BigDecimal value) { progressRatio = value; }
}
