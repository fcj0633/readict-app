package com.readict.support;

import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface TypeProbeMapper {
    UUID roundTripUuid(@Param("value") UUID value);
    String uuidHex(@Param("value") UUID value);
    Instant roundTripInstant(@Param("value") Instant value);
    String database();
}
