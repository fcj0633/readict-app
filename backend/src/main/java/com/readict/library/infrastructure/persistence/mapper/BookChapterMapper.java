package com.readict.library.infrastructure.persistence.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.readict.library.infrastructure.persistence.entity.BookChapterEntity;

public interface BookChapterMapper extends BaseMapper<BookChapterEntity> {
    List<BookChapterEntity> listByArtifact(@Param("artifactId") UUID artifactId);
}
