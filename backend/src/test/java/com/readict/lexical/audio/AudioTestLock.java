package com.readict.lexical.audio;

import java.sql.Connection;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;

/** Prevents independent test JVMs from racing the one global audio pointer. */
final class AudioTestLock implements AutoCloseable {
    private final Connection connection;
    AudioTestLock(DataSource datasource) throws Exception {
        connection=datasource.getConnection();assertThat(connection.getCatalog()).isEqualTo("readict_test");
        try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT GET_LOCK('readict_audio_integration',60)")){result.next();assertThat(result.getInt(1)).isEqualTo(1);}
    }
    @Override public void close() throws Exception {try(var statement=connection.createStatement()){statement.executeQuery("SELECT RELEASE_LOCK('readict_audio_integration')").close();}finally{connection.close();}}
}
