package com.readict.identity.infrastructure.persistence.mapper;

import java.time.Instant;
import org.apache.ibatis.annotations.Param;

public interface RateBucketMapper {
    int lockTarget(@Param("key") byte[] key,@Param("expiry") Instant expiry);
    int cleanup(@Param("now") Instant now);
    int increment(@Param("scope") String scope,@Param("key") byte[] key,@Param("window") Instant window,@Param("expiry") Instant expiry);
    int count(@Param("scope") String scope,@Param("key") byte[] key,@Param("window") Instant window);
    int release(@Param("scope") String scope,@Param("key") byte[] key,@Param("window") Instant window);
}
