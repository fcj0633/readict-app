package com.readict.favorites.infrastructure.persistence.mapper;

import java.util.*;
import org.apache.ibatis.annotations.Param;

/** Complex locks, merges, pagination and immutable snapshots use explicit XML. */
public interface FavoriteMapper {
    Map<String,Object> formEvidence(@Param("args") Object[] args);
    Map<String,Object> bookForSource(@Param("args") Object[] args);
    int insertWord(@Param("args") Object[] args);
    int insertContext(@Param("args") Object[] args);
    int expireReceipt(@Param("args") Object[] args);
    int insertReceipt(@Param("args") Object[] args);
    Map<String,Object> receipt(@Param("args") Object[] args);
    Map<String,Object> existingWord(@Param("args") Object[] args);
    int deleteWord(@Param("args") Object[] args);
    int markDeleted(@Param("args") Object[] args);
    List<Map<String,Object>> matchWords(@Param("args") Object[] args);
    Map<String,Object> word(@Param("args") Object[] args);
    Map<String,Object> lemma(@Param("args") Object[] args);
    int count(@Param("args") Object[] args);
    Map<String,Object> context(@Param("args") Object[] args);
    int detachBook(@Param("args") Object[] args);
    List<Map<String,Object>> list(Map<String,Object> filter);
    Map<String,Object> stats(@Param("user") byte[] user);
    List<Map<String,Object>> sources(@Param("user") byte[] user);
    List<Map<String,Object>> contexts(@Param("user") byte[] user,@Param("favorite") byte[] favorite);
}
