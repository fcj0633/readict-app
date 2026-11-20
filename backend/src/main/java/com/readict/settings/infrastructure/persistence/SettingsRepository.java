package com.readict.settings.infrastructure.persistence;

import java.util.UUID;
import org.springframework.stereotype.Repository;
import com.readict.settings.infrastructure.persistence.mapper.SettingsMapper;
import com.readict.settings.infrastructure.persistence.entity.SettingsEntity;

@Repository
public class SettingsRepository {
    private final SettingsMapper mapper;
    public SettingsRepository(SettingsMapper mapper) { this.mapper=mapper; }
    public void insert(SettingsEntity value) { mapper.insert(value); }
    public SettingsEntity find(UUID user) { return mapper.selectById(user); }
    public SettingsEntity lock(UUID user) { return mapper.lockSettings(user); }
    public int save(SettingsEntity value,long expected) { return mapper.saveVersion(value,expected); }
}
