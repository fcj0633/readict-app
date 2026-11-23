package com.readict.reading.infrastructure.persistence.mapper;

import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.readict.reading.infrastructure.persistence.entity.ReadingProgressEntity;

public interface ReadingProgressMapper extends BaseMapper<ReadingProgressEntity> {
    ReadingProgressEntity find(@Param("bookId") UUID bookId);

    int updateProgress(@Param("entity") ReadingProgressEntity entity);

    int deleteByBook(@Param("bookId") UUID bookId);
}
