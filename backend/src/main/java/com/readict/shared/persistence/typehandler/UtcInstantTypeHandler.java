package com.readict.shared.persistence.typehandler;

import java.sql.*;
import java.time.Instant;
import java.util.Calendar;
import java.util.TimeZone;
import org.apache.ibatis.type.*;

@MappedTypes(Instant.class)
@MappedJdbcTypes(value = JdbcType.TIMESTAMP, includeNullJdbcType = true)
public class UtcInstantTypeHandler extends BaseTypeHandler<Instant> {
    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
    private static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
    @Override public void setNonNullParameter(PreparedStatement ps, int i, Instant value, JdbcType type) throws SQLException { ps.setTimestamp(i, Timestamp.from(value), utc()); }
    @Override public Instant getNullableResult(ResultSet rs, String column) throws SQLException { return instant(rs.getTimestamp(column, utc())); }
    @Override public Instant getNullableResult(ResultSet rs, int column) throws SQLException { return instant(rs.getTimestamp(column, utc())); }
    @Override public Instant getNullableResult(CallableStatement cs, int column) throws SQLException { return instant(cs.getTimestamp(column, utc())); }
}
