package com.readict.lexical.infrastructure.persistence.mapper;

import java.util.*;
import org.apache.ibatis.annotations.Param;

public interface AudioMapper {
    Map<String,Object> active();
    Map<String,Object> lockActive();
    Map<String,Object> dataset(byte[] id);
    Map<String,Object> lockDataset(byte[] id);
    int create(Map<String,Object> row);
    int acquire(Map<String,Object> owner);
    int heartbeat(Map<String,Object> owner);
    int relinquish(Map<String,Object> owner);
    int checkpoint(Map<String,Object> row);
    int ready(Map<String,Object> owner);
    int failed(Map<String,Object> owner);
    int assets(@Param("dataset") byte[] dataset,@Param("rows") List<Map<String,Object>> rows);
    int mappings(@Param("dataset") byte[] dataset,@Param("rows") List<Map<String,Object>> rows);
    long countAssets(byte[] id);
    long countMappings(byte[] id);
    List<Map<String,Object>> assetPage(@Param("dataset") byte[] dataset,@Param("after") long after);
    List<Map<String,Object>> find(@Param("dataset") byte[] dataset,@Param("word") String word);
    Map<String,Object> resource(byte[] id);
    int activate(@Param("dataset") byte[] dataset,@Param("version") long version);
}
