package com.readict.lexical.infrastructure.persistence.mapper;

import java.util.*;
import org.apache.ibatis.annotations.Param;

public interface DictionaryMapper {
    Map<String,Object> dataset(@Param("id") byte[] id);
    Map<String,Object> lockDataset(@Param("id") byte[] id);
    Map<String,Object> release(@Param("source") String source,@Param("version") String version,@Param("converter") String converter);
    int create(Map<String,Object> row);
    int acquire(Map<String,Object> row);
    int heartbeat(Map<String,Object> row);
    int checkpoint(Map<String,Object> row);
    int ready(Map<String,Object> row);
    int relinquish(Map<String,Object> row);
    int failed(Map<String,Object> row);
    int entries(@Param("dataset") byte[] dataset,@Param("rows") List<Map<String,Object>> rows);
    int senses(@Param("dataset") byte[] dataset,@Param("rows") List<Map<String,Object>> rows);
    int forms(@Param("dataset") byte[] dataset,@Param("rows") List<Map<String,Object>> rows);
    Map<String,Object> active();
    Map<String,Object> lockActive();
    int activate(@Param("id") byte[] id,@Param("version") long version);
    long countEntries(@Param("id") byte[] id);
    long countSenses(@Param("id") byte[] id);
    long countForms(@Param("id") byte[] id);
    long emptyEntries(@Param("id") byte[] id);
    List<Map<String,Object>> findEntries(@Param("id") byte[] id,@Param("key") String key);
    List<Map<String,Object>> findSenses(@Param("id") byte[] id,@Param("entries") List<byte[]> entries);
}
