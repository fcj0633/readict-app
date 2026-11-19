package com.readict.operations.infrastructure.persistence;

import org.springframework.stereotype.Repository;
import com.readict.operations.infrastructure.persistence.mapper.SystemMetadataMapper;

@Repository
public class SystemMetadataRepository {
    private final SystemMetadataMapper mapper;
    public SystemMetadataRepository(SystemMetadataMapper mapper) { this.mapper = mapper; }
    public boolean hasApplicationBaseline() {
        var metadata = mapper.selectMetadata("application");
        return metadata != null && "readict".equals(metadata.getMetadataValue());
    }
}
