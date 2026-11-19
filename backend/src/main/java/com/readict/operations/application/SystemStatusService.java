package com.readict.operations.application;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.readict.operations.infrastructure.persistence.SystemMetadataRepository;

@Service
public class SystemStatusService {
    private final SystemMetadataRepository repository;
    public SystemStatusService(SystemMetadataRepository repository) { this.repository = repository; }
    @Transactional(readOnly = true)
    public void checkReady() {
        try {
            if (!repository.hasApplicationBaseline()) throw new SystemNotReadyException();
        } catch (DataAccessException ex) { throw new SystemNotReadyException(); }
    }
}
