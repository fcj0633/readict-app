package com.readict.shared.persistence.typehandler;

import java.nio.ByteBuffer;
import java.sql.*;
import java.util.UUID;
import org.apache.ibatis.type.*;

@MappedTypes(UUID.class)
@MappedJdbcTypes(value = {JdbcType.BINARY, JdbcType.VARBINARY}, includeNullJdbcType = true)
public class UuidBinaryTypeHandler extends BaseTypeHandler<UUID> {
    public static byte[] encode(UUID id) {
        if (id == null) return null;
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
    public static UUID decode(byte[] bytes) {
        if (bytes == null) return null;
        if (bytes.length != 16) throw new IllegalArgumentException("Invalid UUID bytes");
        ByteBuffer b = ByteBuffer.wrap(bytes);
        return new UUID(b.getLong(), b.getLong());
    }
    @Override public void setNonNullParameter(PreparedStatement ps, int i, UUID value, JdbcType type) throws SQLException { ps.setBytes(i, encode(value)); }
    @Override public UUID getNullableResult(ResultSet rs, String column) throws SQLException { return decode(rs.getBytes(column)); }
    @Override public UUID getNullableResult(ResultSet rs, int column) throws SQLException { return decode(rs.getBytes(column)); }
    @Override public UUID getNullableResult(CallableStatement cs, int column) throws SQLException { return decode(cs.getBytes(column)); }
}
