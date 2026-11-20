package com.readict.settings.infrastructure.persistence.mapper;
import java.util.UUID;
import java.time.Instant;
import org.apache.ibatis.annotations.Param;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.readict.settings.infrastructure.persistence.entity.SettingsEntity;
public interface SettingsMapper extends BaseMapper<SettingsEntity> {
    SettingsEntity lockSettings(@Param("id") UUID id);
    int saveVersion(@Param("value") SettingsEntity value,@Param("expected") long expected);
}
