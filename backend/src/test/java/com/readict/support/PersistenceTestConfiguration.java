package com.readict.support;

import com.readict.operations.infrastructure.persistence.entity.SystemMetadataEntity;
import com.readict.operations.infrastructure.persistence.mapper.SystemMetadataMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@TestConfiguration(proxyBeanMethods = false)
@MapperScan(basePackageClasses = TypeProbeMapper.class)
public class PersistenceTestConfiguration {
    @Bean TransactionProbe transactionProbe(SystemMetadataMapper mapper) { return new TransactionProbe(mapper); }
    public static class TransactionProbe {
        private final SystemMetadataMapper mapper;
        public TransactionProbe(SystemMetadataMapper mapper) { this.mapper = mapper; }
        @Transactional public void write(String key, boolean fail) {
            var entity = new SystemMetadataEntity();
            entity.setMetadataKey(key); entity.setMetadataValue("inserted"); entity.setVersion(0L); entity.setUpdatedAt(Instant.now());
            mapper.insert(entity);
            if (mapper.updateIfVersionMatches(key,"updated",0,Instant.now()) != 1) throw new IllegalStateException("update failed");
            if (fail) throw new IllegalStateException("controlled rollback");
        }
    }
}
