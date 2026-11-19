package com.readict.operations.infrastructure.persistence.mapper;

import java.time.Instant;
import org.apache.ibatis.annotations.Param;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.readict.operations.infrastructure.persistence.entity.SystemMetadataEntity;

public interface SystemMetadataMapper extends BaseMapper<SystemMetadataEntity> {
    SystemMetadataEntity selectMetadata(@Param("key") String key);
    int updateIfVersionMatches(@Param("key") String key, @Param("value") String value,
            @Param("expectedVersion") long expectedVersion, @Param("updatedAt") Instant updatedAt);
}
