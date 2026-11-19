package com.readict;

import com.readict.shared.persistence.typehandler.*;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.type.JdbcType;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class TypeHandlerTest {
    @Test void uuidEncodingHasCanonicalByteOrderAndRejectsInvalidLengths() {
        UUID id = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        assertThat(HexFormat.of().formatHex(UuidBinaryTypeHandler.encode(id))).isEqualTo("00112233445566778899aabbccddeeff");
        assertThat(UuidBinaryTypeHandler.decode(UuidBinaryTypeHandler.encode(id))).isEqualTo(id);
        assertThat(UuidBinaryTypeHandler.decode(null)).isNull();
        for (int length : new int[] {0, 15, 17}) assertThatThrownBy(() -> UuidBinaryTypeHandler.decode(new byte[length])).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void uuidHandlesAllJdbcReadPathsAndNulls() throws Exception {
        var handler = new UuidBinaryTypeHandler(); var rs = mock(ResultSet.class); var cs = mock(CallableStatement.class);
        var ps = mock(PreparedStatement.class); var id = UUID.randomUUID(); byte[] value = UuidBinaryTypeHandler.encode(id);
        when(rs.getBytes("id")).thenReturn(value); when(rs.getBytes(1)).thenReturn(value); when(cs.getBytes(1)).thenReturn(value);
        assertThat(handler.getNullableResult(rs,"id")).isEqualTo(id); assertThat(handler.getNullableResult(rs,1)).isEqualTo(id); assertThat(handler.getNullableResult(cs,1)).isEqualTo(id);
        when(rs.getBytes("id")).thenReturn(null); when(rs.getBytes(1)).thenReturn(null); when(cs.getBytes(1)).thenReturn(null);
        assertThat(handler.getNullableResult(rs,"id")).isNull(); assertThat(handler.getNullableResult(rs,1)).isNull(); assertThat(handler.getNullableResult(cs,1)).isNull();
        handler.setParameter(ps,1,null,JdbcType.BINARY); verify(ps).setNull(1,Types.BINARY);
        handler.setNonNullParameter(ps,2,id,JdbcType.BINARY); verify(ps).setBytes(2,value);
    }
    @Test void instantUsesNewUtcCalendarOnAllJdbcPathsRegardlessOfDefaultZone() throws Exception {
        var saved = TimeZone.getDefault();
        try {
            for (String zone : List.of("Asia/Shanghai", "America/New_York")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                var handler = new UtcInstantTypeHandler(); var rs = mock(ResultSet.class); var cs = mock(CallableStatement.class); var ps = mock(PreparedStatement.class);
                Instant instant = Instant.parse("2026-11-19T01:02:03.456Z"); var timestamp = Timestamp.from(instant);
                when(rs.getTimestamp(eq("t"),any(Calendar.class))).thenReturn(timestamp);
                when(rs.getTimestamp(eq(1),any(Calendar.class))).thenReturn(timestamp);
                when(cs.getTimestamp(eq(1),any(Calendar.class))).thenReturn(timestamp);
                assertThat(handler.getNullableResult(rs,"t")).isEqualTo(instant); assertThat(handler.getNullableResult(rs,1)).isEqualTo(instant); assertThat(handler.getNullableResult(cs,1)).isEqualTo(instant);
                handler.setNonNullParameter(ps,1,instant,JdbcType.TIMESTAMP); handler.setNonNullParameter(ps,1,instant,JdbcType.TIMESTAMP);
                var calendars = ArgumentCaptor.forClass(Calendar.class); verify(ps,times(2)).setTimestamp(eq(1),eq(timestamp),calendars.capture());
                assertThat(calendars.getAllValues().get(0)).isNotSameAs(calendars.getAllValues().get(1));
                assertThat(calendars.getAllValues()).allSatisfy(c -> assertThat(c.getTimeZone().getID()).isEqualTo("UTC"));
                when(rs.getTimestamp(eq("t"),any(Calendar.class))).thenReturn(null); when(rs.getTimestamp(eq(1),any(Calendar.class))).thenReturn(null); when(cs.getTimestamp(eq(1),any(Calendar.class))).thenReturn(null);
                assertThat(handler.getNullableResult(rs,"t")).isNull(); assertThat(handler.getNullableResult(rs,1)).isNull(); assertThat(handler.getNullableResult(cs,1)).isNull();
                handler.setParameter(ps,2,null,JdbcType.TIMESTAMP); verify(ps).setNull(2,Types.TIMESTAMP);
            }
        } finally { TimeZone.setDefault(saved); }
    }
}
