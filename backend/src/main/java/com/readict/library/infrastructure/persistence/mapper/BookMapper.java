package com.readict.library.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.readict.library.infrastructure.persistence.entity.BookEntity;

public interface BookMapper extends BaseMapper<BookEntity> {
    BookEntity findOwned(@Param("id") UUID id, @Param("userId") UUID userId);

    BookEntity lockOwned(@Param("id") UUID id, @Param("userId") UUID userId);

    BookEntity findBySource(@Param("userId") UUID userId, @Param("sourceHash") byte[] sourceHash);

    List<BookEntity> shelf(@Param("userId") UUID userId, @Param("q") String q,
                           @Param("cursorTime") Instant cursorTime, @Param("cursorId") UUID cursorId,
                           @Param("limit") int limit);

    int markReady(@Param("id") UUID id, @Param("artifactId") UUID artifactId, @Param("now") Instant now);

    int markFailed(@Param("id") UUID id, @Param("now") Instant now);

    int markDeleting(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);

    int reimport(@Param("id") UUID id, @Param("sourceSize") long sourceSize,
                 @Param("originalFilename") String originalFilename, @Param("now") Instant now);

    int updateMetadata(@Param("id") UUID id, @Param("title") String title,
                       @Param("author") String author, @Param("now") Instant now);

    int markFailedWithoutOpenTask(@Param("now") Instant now);

    int touchRead(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);
}
