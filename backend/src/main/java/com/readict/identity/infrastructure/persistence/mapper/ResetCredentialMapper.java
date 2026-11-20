package com.readict.identity.infrastructure.persistence.mapper;
import java.util.UUID;
import java.time.Instant;
import org.apache.ibatis.annotations.Param;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.readict.identity.infrastructure.persistence.entity.ResetCredentialEntity;
public interface ResetCredentialMapper extends BaseMapper<ResetCredentialEntity> {
    ResetCredentialEntity findHash(@Param("hash") byte[] hash);
    ResetCredentialEntity lockCredential(@Param("id") UUID id);
    int cleanup(@Param("before") Instant before);
    int revokeUser(@Param("id") UUID id,@Param("now") Instant now);
}
