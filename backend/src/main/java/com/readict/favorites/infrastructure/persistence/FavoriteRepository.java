package com.readict.favorites.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Repository;
import com.readict.shared.persistence.typehandler.UuidBinaryTypeHandler;
import com.readict.favorites.infrastructure.persistence.mapper.FavoriteMapper;

/** Named, parameter-bound XML statements share the application's Spring transaction. */
@Repository
public class FavoriteRepository {
    private final FavoriteMapper mapper;
    public FavoriteRepository(FavoriteMapper mapper) { this.mapper=mapper; }
    public static byte[] bytes(UUID id) { return UuidBinaryTypeHandler.encode(id); }
    public static UUID id(Map<String,Object> row, String key) { return UuidBinaryTypeHandler.decode((byte[]) row.get(key)); }
    public static String text(Map<String,Object> row, String key) { return (String) row.get(key); }
    public static Instant time(Map<String,Object> row, String key) {
        Object value=row.get(key);
        if(value instanceof java.time.LocalDateTime local) return local.toInstant(java.time.ZoneOffset.UTC);
        if(value instanceof Timestamp stamp) return stamp.toInstant();
        throw new IllegalStateException("Unexpected database timestamp type");
    }

    public Map<String,Object> word(UUID user,UUID favorite,boolean lock) { return mapper.word(new Object[]{bytes(user),bytes(favorite),lock}); }
    public Map<String,Object> lemma(UUID user,String lemma,boolean lock) { return mapper.lemma(new Object[]{bytes(user),lemma,lock}); }
    public Map<String,Object> formEvidence(Object... args) { return mapper.formEvidence(args); }
    public int insertWord(Object... args) { return mapper.insertWord(args); }
    public int insertExample(Object... args) { return mapper.insertExample(args); }
    public int expireReceipt(Object... args) { return mapper.expireReceipt(args); }
    public int insertReceipt(Object... args) { return mapper.insertReceipt(args); }
    public Map<String,Object> receipt(Object... args) { return mapper.receipt(args); }
    public Map<String,Object> existingWord(Object... args) { return mapper.existingWord(args); }
    public int deleteWord(Object... args) { return mapper.deleteWord(args); }
    public int markDeleted(Object... args) { return mapper.markDeleted(args); }
    public List<Map<String,Object>> matchWords(Object... args) { return mapper.matchWords(args); }
    public List<Map<String,Object>> list(Map<String,Object> filter) { return mapper.list(filter); }
    public Map<String,Object> stats(UUID user) { return mapper.stats(bytes(user)); }
    public List<Map<String,Object>> sources(UUID user) { return mapper.sources(bytes(user)); }
    public List<Map<String,Object>> examples(UUID user,UUID favorite) { return mapper.examples(bytes(user),bytes(favorite)); }
}
