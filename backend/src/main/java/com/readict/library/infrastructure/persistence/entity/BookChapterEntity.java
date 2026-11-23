package com.readict.library.infrastructure.persistence.entity;

import java.time.Instant;
import java.util.UUID;
import com.baomidou.mybatisplus.annotation.*;
import com.readict.shared.persistence.typehandler.UtcInstantTypeHandler;

@TableName(value = "book_chapter", autoResultMap = true)
public class BookChapterEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID artifactId;
    private int spineIndex;
    private String spineHref;
    private byte[] hrefHash;
    private String title;
    private long textLength;
    private long cumulativeTextLength;
    @TableField(typeHandler = UtcInstantTypeHandler.class)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public UUID getArtifactId() { return artifactId; }
    public void setArtifactId(UUID value) { artifactId = value; }
    public int getSpineIndex() { return spineIndex; }
    public void setSpineIndex(int value) { spineIndex = value; }
    public String getSpineHref() { return spineHref; }
    public void setSpineHref(String value) { spineHref = value; }
    public byte[] getHrefHash() { return hrefHash; }
    public void setHrefHash(byte[] value) { hrefHash = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public long getTextLength() { return textLength; }
    public void setTextLength(long value) { textLength = value; }
    public long getCumulativeTextLength() { return cumulativeTextLength; }
    public void setCumulativeTextLength(long value) { cumulativeTextLength = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
}
