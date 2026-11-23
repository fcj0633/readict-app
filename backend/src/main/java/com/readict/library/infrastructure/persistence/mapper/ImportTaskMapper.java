package com.readict.library.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.readict.library.infrastructure.persistence.entity.ImportTaskEntity;

public interface ImportTaskMapper extends BaseMapper<ImportTaskEntity> {
    ImportTaskEntity findByRequest(@Param("userId") UUID userId, @Param("requestId") UUID requestId);

    ImportTaskEntity findOwned(@Param("id") UUID id, @Param("userId") UUID userId);

    ImportTaskEntity currentForBook(@Param("bookId") UUID bookId);

    int countActive(@Param("userId") UUID userId);

    int countQueued();

    int markCleanupPending(@Param("bookId") UUID bookId);

    UUID candidate(@Param("now") Instant now);

    int claim(@Param("id") UUID id, @Param("token") UUID token, @Param("until") Instant until, @Param("now") Instant now);

    int renew(@Param("id") UUID id, @Param("token") UUID token, @Param("until") Instant until, @Param("now") Instant now);

    int stage(@Param("id") UUID id, @Param("token") UUID token, @Param("stage") String stage, @Param("now") Instant now);

    int succeed(@Param("id") UUID id, @Param("token") UUID token, @Param("now") Instant now);

    int fail(@Param("id") UUID id, @Param("token") UUID token, @Param("code") String code,
             @Param("message") String message, @Param("now") Instant now);

    int failQueued(@Param("id") UUID id, @Param("code") String code,
                   @Param("message") String message, @Param("now") Instant now);

    int requeue(@Param("id") UUID id, @Param("token") UUID token, @Param("nextAt") Instant nextAt, @Param("now") Instant now);

    int requeueExpired(@Param("now") Instant now, @Param("nextAt") Instant nextAt, @Param("maxAttempts") int maxAttempts);

    int failExpired(@Param("now") Instant now);
}
