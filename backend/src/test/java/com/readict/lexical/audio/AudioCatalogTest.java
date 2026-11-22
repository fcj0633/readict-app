package com.readict.lexical.audio;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import com.readict.lexical.infrastructure.persistence.mapper.AudioMapper;
import com.readict.lexical.core.DictionaryImporter;
import org.springframework.dao.DataAccessResourceFailureException;

class AudioCatalogTest {
    AudioMapper db=mock(AudioMapper.class);
    static class MutableClock extends Clock {long ms;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(ms);}}
    MutableClock clock=new MutableClock();AudioCatalog catalog=new AudioCatalog(db,clock);
    Map<String,Object> active(){return Map.of("dataset_id",DictionaryImporter.bytes(UUID.randomUUID().toString()),"status","READY","manifest","{\"version\":\"test\"}");}
    Map<String,Object> recording(String accent,int sha){byte[] digest=new byte[32];digest[0]=(byte)sha;return Map.of("id",DictionaryImporter.bytes(UUID.randomUUID().toString()),"output_sha",digest,"accent",accent,"duration_ms",500);}
    @Test void unconfiguredAndDatabaseFailureAreDifferent(){when(db.active()).thenReturn(Map.of());assertThat(catalog.find("sample","col-normalize-v1").status()).isEqualTo("notConfigured");when(db.active()).thenThrow(new DataAccessResourceFailureException("private"));assertThatThrownBy(()->catalog.find("sample","col-normalize-v1")).hasMessage("AUDIO_DATABASE_UNAVAILABLE");}
    @Test void sameHashDeduplicatesButAmbiguousAccentDoesNotHideOtherAccent(){when(db.active()).thenReturn(active());when(db.find(any(),any())).thenReturn(List.of(recording("en-GB",1),recording("en-GB",2),recording("en-US",3),recording("en-US",3)));var r=catalog.find("sample","col-normalize-v1");assertThat(r.status()).isEqualTo("ambiguous");assertThat(r.items()).hasSize(1);assertThat(r.items().getFirst().accent()).isEqualTo("en-US");}
    @Test void activeIsNotCachedAndEntriesExpireAfterFiveMinutes(){when(db.active()).thenReturn(active());when(db.find(any(),any())).thenReturn(List.of(recording("en-GB",1)));catalog.find("sample","col-normalize-v1");catalog.find("Sample","col-normalize-v1");verify(db,times(2)).active();verify(db,times(1)).find(any(),any());clock.ms=300000;catalog.find("sample","col-normalize-v1");verify(db,times(2)).find(any(),any());when(db.active()).thenReturn(active());catalog.find("sample","col-normalize-v1");verify(db,times(3)).find(any(),any());}
    @Test void unsupportedNormalizationDoesNotQueryDatabase(){assertThatThrownBy(()->catalog.find("sample","future")).hasMessage("AUDIO_NORMALIZATION_UNSUPPORTED");verifyNoInteractions(db);}
    @Test void cacheEvictsAfterFiveThousandKeysAndFaultsAreNeverCached(){when(db.active()).thenReturn(active());when(db.find(any(),any())).thenReturn(List.of());for(int i=0;i<5001;i++)catalog.find("word"+i,"col-normalize-v1");catalog.find("word0","col-normalize-v1");verify(db,times(5002)).find(any(),any());when(db.find(any(),any())).thenThrow(new DataAccessResourceFailureException("private"));assertThatThrownBy(()->catalog.find("fault","col-normalize-v1")).hasMessage("AUDIO_DATABASE_UNAVAILABLE");assertThatThrownBy(()->catalog.find("fault","col-normalize-v1")).hasMessage("AUDIO_DATABASE_UNAVAILABLE");verify(db,times(2)).find(any(),eq("fault"));}
}
