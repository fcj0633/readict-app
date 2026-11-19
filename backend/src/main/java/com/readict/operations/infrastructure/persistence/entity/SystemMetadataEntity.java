package com.readict.operations.infrastructure.persistence.entity;

import java.time.Instant;
import com.baomidou.mybatisplus.annotation.*;
import com.readict.shared.persistence.typehandler.UtcInstantTypeHandler;

@TableName(value = "system_metadata", autoResultMap = true)
public class SystemMetadataEntity {
    @TableId(value = "metadata_key", type = IdType.INPUT)
    private String metadataKey;
    private String metadataValue;
    private Long version;
    @TableField(value = "updated_at", typeHandler = UtcInstantTypeHandler.class)
    private Instant updatedAt;
    public String getMetadataKey() { return metadataKey; }
    public void setMetadataKey(String value) { metadataKey = value; }
    public String getMetadataValue() { return metadataValue; }
    public void setMetadataValue(String value) { metadataValue = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long value) { version = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
}
