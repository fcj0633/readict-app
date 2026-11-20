package com.readict.identity.infrastructure.persistence.mapper;
import java.util.UUID;
import java.time.Instant;
import org.apache.ibatis.annotations.Param;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.readict.identity.infrastructure.persistence.entity.ChallengeEntity;
public interface ChallengeMapper extends BaseMapper<ChallengeEntity> {
    ChallengeEntity latest(@Param("target") byte[] target,@Param("purpose") String purpose);
    ChallengeEntity lockChallenge(@Param("id") UUID id);
    int supersedeUser(@Param("id") UUID id);
    int delivered(@Param("id") UUID id,@Param("state") String state);
    int cleanup(@Param("before") Instant before);
    int expireStale(@Param("now") Instant now);
}
