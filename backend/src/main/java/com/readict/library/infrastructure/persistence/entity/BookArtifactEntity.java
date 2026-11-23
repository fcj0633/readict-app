package com.readict.library.infrastructure.persistence.entity;

import java.time.Instant;
import java.util.UUID;
import com.baomidou.mybatisplus.annotation.*;
import com.readict.shared.persistence.typehandler.UtcInstantTypeHandler;

@TableName(value = "book_artifact", autoResultMap = true)
public class BookArtifactEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID bookId;
    private byte[] artifactHash;
    private String pipelineVersion;
    private String epubStorageKey;
    private String indexStorageKey;
    private String manifestStorageKey;
    private String coverStorageKey;
    private String coverMime;
    private byte[] coverHash;
    private long totalUtf16;
    private String status;
    @TableField(typeHandler = UtcInstantTypeHandler.class)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public UUID getBookId() { return bookId; }
    public void setBookId(UUID value) { bookId = value; }
    public byte[] getArtifactHash() { return artifactHash; }
    public void setArtifactHash(byte[] value) { artifactHash = value; }
    public String getPipelineVersion() { return pipelineVersion; }
    public void setPipelineVersion(String value) { pipelineVersion = value; }
    public String getEpubStorageKey() { return epubStorageKey; }
    public void setEpubStorageKey(String value) { epubStorageKey = value; }
    public String getIndexStorageKey() { return indexStorageKey; }
    public void setIndexStorageKey(String value) { indexStorageKey = value; }
    public String getManifestStorageKey() { return manifestStorageKey; }
    public void setManifestStorageKey(String value) { manifestStorageKey = value; }
    public String getCoverStorageKey() { return coverStorageKey; }
    public void setCoverStorageKey(String value) { coverStorageKey = value; }
    public String getCoverMime() { return coverMime; }
    public void setCoverMime(String value) { coverMime = value; }
    public byte[] getCoverHash() { return coverHash; }
    public void setCoverHash(byte[] value) { coverHash = value; }
    public long getTotalUtf16() { return totalUtf16; }
    public void setTotalUtf16(long value) { totalUtf16 = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
}
