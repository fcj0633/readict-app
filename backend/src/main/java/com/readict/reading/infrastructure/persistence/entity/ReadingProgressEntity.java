package com.readict.reading.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import com.baomidou.mybatisplus.annotation.*;
import com.readict.shared.persistence.typehandler.UtcInstantTypeHandler;

@TableName(value = "reading_progress", autoResultMap = true)
public class ReadingProgressEntity {
    @TableId(type = IdType.INPUT)
    private UUID bookId;
    private UUID userId;
    private UUID artifactId;
    private String spineHref;
    private String cfi;
    private String blockId;
    private Integer blockOffset;
    private String quoteExact;
    private String quotePrefix;
    private String quoteSuffix;
    private BigDecimal progressRatio;
    private long version;
    private UUID lastRequestId;
    private byte[] lastRequestHash;
    @TableField(typeHandler = UtcInstantTypeHandler.class)
    private Instant updatedAt;

    public UUID getBookId() { return bookId; }
    public void setBookId(UUID value) { bookId = value; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    public UUID getArtifactId() { return artifactId; }
    public void setArtifactId(UUID value) { artifactId = value; }
    public String getSpineHref() { return spineHref; }
    public void setSpineHref(String value) { spineHref = value; }
    public String getCfi() { return cfi; }
    public void setCfi(String value) { cfi = value; }
    public String getBlockId() { return blockId; }
    public void setBlockId(String value) { blockId = value; }
    public Integer getBlockOffset() { return blockOffset; }
    public void setBlockOffset(Integer value) { blockOffset = value; }
    public String getQuoteExact() { return quoteExact; }
    public void setQuoteExact(String value) { quoteExact = value; }
    public String getQuotePrefix() { return quotePrefix; }
    public void setQuotePrefix(String value) { quotePrefix = value; }
    public String getQuoteSuffix() { return quoteSuffix; }
    public void setQuoteSuffix(String value) { quoteSuffix = value; }
    public BigDecimal getProgressRatio() { return progressRatio; }
    public void setProgressRatio(BigDecimal value) { progressRatio = value; }
    public long getVersion() { return version; }
    public void setVersion(long value) { version = value; }
    public UUID getLastRequestId() { return lastRequestId; }
    public void setLastRequestId(UUID value) { lastRequestId = value; }
    public byte[] getLastRequestHash() { return lastRequestHash; }
    public void setLastRequestHash(byte[] value) { lastRequestHash = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
}
