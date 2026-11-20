package com.readict.identity.infrastructure.persistence.mapper;
import java.util.UUID;
import java.time.Instant;
import org.apache.ibatis.annotations.Param;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.readict.identity.infrastructure.persistence.entity.UserEntity;
public interface UserMapper extends BaseMapper<UserEntity> {
    UserEntity findIdentifier(@Param("type") String type,@Param("key") String key);
    UserEntity lockUser(@Param("id") UUID id);
    int changePassword(@Param("id") UUID id,@Param("hash") String hash,@Param("version") long version,@Param("now") Instant now);
}
