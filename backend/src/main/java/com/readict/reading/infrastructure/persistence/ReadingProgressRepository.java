package com.readict.reading.infrastructure.persistence;

import java.util.UUID;
import org.springframework.stereotype.Repository;
import com.readict.reading.infrastructure.persistence.entity.ReadingProgressEntity;
import com.readict.reading.infrastructure.persistence.mapper.ReadingProgressMapper;

@Repository
public class ReadingProgressRepository {
    private final ReadingProgressMapper mapper;

    public ReadingProgressRepository(ReadingProgressMapper mapper) { this.mapper = mapper; }

    public ReadingProgressEntity find(UUID bookId) { return mapper.find(bookId); }

    public void insert(ReadingProgressEntity entity) { mapper.insert(entity); }

    /** Entity.version must already be the new version; SQL requires version-1 to match. */
    public boolean update(ReadingProgressEntity entity) { return mapper.updateProgress(entity) == 1; }

    public void deleteByBook(UUID bookId) { mapper.deleteByBook(bookId); }
}
