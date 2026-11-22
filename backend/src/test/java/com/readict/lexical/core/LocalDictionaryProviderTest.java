package com.readict.lexical.core;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.readict.lexical.core.DictionaryJson.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import com.readict.lexical.infrastructure.persistence.mapper.DictionaryMapper;
import com.readict.shared.persistence.typehandler.UuidBinaryTypeHandler;
import org.springframework.dao.DataAccessResourceFailureException;

class LocalDictionaryProviderTest {
 static final class MutableClock extends Clock {long ms;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(ms);}}
 DictionaryMapper db;MutableClock clock;LocalDictionaryProvider provider;
 byte[] id=UuidBinaryTypeHandler.encode(UUID.randomUUID());
 @BeforeEach void init(){db=mock(DictionaryMapper.class);clock=new MutableClock();provider=new LocalDictionaryProvider(db,clock);when(db.active()).thenReturn(Map.of("dataset_id",id,"status","READY","manifest","{\"datasetVersion\":\"v1\",\"normalizationVersion\":\"col-normalize-v1\",\"rights\":{\"policyVersion\":\"unverified\"}}"));when(db.findEntries(any(),any())).thenReturn(List.of());}
 @Test void notFoundCacheExpiresAndActivePointerIsNeverCached(){assertThat(provider.lookup("absent",null).status()).isEqualTo("NOT_FOUND");provider.lookup("absent",null);verify(db,times(2)).active();verify(db,times(1)).findEntries(any(),any());clock.ms=30000;provider.lookup("absent",null);verify(db,times(2)).findEntries(any(),any());}
 @Test void switchingDatasetDoesNotReuseNegativeCache(){provider.lookup("absent",null);when(db.active()).thenReturn(Map.of("dataset_id",UuidBinaryTypeHandler.encode(UUID.randomUUID()),"status","READY","manifest","{\"datasetVersion\":\"v2\",\"normalizationVersion\":\"col-normalize-v1\",\"rights\":{}}"));provider.lookup("absent",null);verify(db,times(2)).findEntries(any(),any());}
 @Test void faultIsNotNegativeCachedAndMissingDatasetIsNotNotFound(){when(db.active()).thenReturn(Map.of("version",0));assertThatThrownBy(()->provider.lookup("book",null)).hasMessage("DICTIONARY_NOT_CONFIGURED");when(db.active()).thenThrow(new DataAccessResourceFailureException("secret"));assertThatThrownBy(()->provider.lookup("book",null)).hasMessage("DICTIONARY_UNAVAILABLE");assertThat(provider.cachedKeys()).isZero();}
 @Test void invalidQueryDoesNotTouchDatabaseAndCapacityIsBounded(){assertThat(provider.lookup("two words",null).status()).isEqualTo("UNSUPPORTED_QUERY");verifyNoInteractions(db);for(int i=0;i<2005;i++)provider.lookup("word"+i,null);assertThat(provider.cachedKeys()).isEqualTo(2000);assertThat(provider.estimatedCacheBytes()).isLessThanOrEqualTo(16*1024*1024);}
 @Test void ambiguousCandidatesAreRetainedAndPosOnlyReordersSenses(){
  String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString();when(db.findEntries(any(),any())).thenReturn(List.of(Map.of("id",DictionaryImporter.bytes(a),"metadata","{\"entryId\":\""+a+"\",\"lemmaKey\":\"saw\",\"references\":[],\"notes\":[]}"),Map.of("id",DictionaryImporter.bytes(b),"metadata","{\"entryId\":\""+b+"\",\"lemmaKey\":\"see\",\"references\":[],\"notes\":[]}")));
  when(db.findSenses(any(),any())).thenReturn(List.of(Map.of("entry_id",DictionaryImporter.bytes(a),"metadata","{\"pos\":\"noun\",\"zh\":\"自建一\",\"providerOrder\":0}"),Map.of("entry_id",DictionaryImporter.bytes(a),"metadata","{\"pos\":\"verb\",\"zh\":\"自建二\",\"providerOrder\":1}"),Map.of("entry_id",DictionaryImporter.bytes(b),"metadata","{\"pos\":\"verb\",\"zh\":\"自建三\",\"providerOrder\":0}")));
  var r=provider.lookup("saw","verb");assertThat(r.status()).isEqualTo("AMBIGUOUS");assertThat(r.resolvedLemmaKey()).isNull();assertThat(r.regions().size()).isEqualTo(2);assertThat(r.regions().get(0).path("senses").get(0).path("pos").asText()).isEqualTo("verb");assertThat(provider.lookup("saw",null).regions().get(0).path("senses").get(0).path("pos").asText()).isEqualTo("noun");
 }
 void successfulFixture(String zh){String entry=UUID.randomUUID().toString();when(db.findEntries(any(),any())).thenReturn(List.of(Map.of("id",DictionaryImporter.bytes(entry),"metadata","{\"entryId\":\""+entry+"\",\"lemmaKey\":\"sample\",\"references\":[],\"notes\":[]}")));var senses=new ArrayList<Map<String,Object>>();for(int i=0;i<5;i++)senses.add(Map.of("entry_id",DictionaryImporter.bytes(entry),"metadata",json(Map.of("pos","noun","zh",zh,"providerOrder",i))));when(db.findSenses(any(),any())).thenReturn(senses);}
 @Test void successfulCacheExpiresAtTenMinutes(){successfulFixture("自建");provider.lookup("sample",null);clock.ms=599999;provider.lookup("sample",null);verify(db,times(1)).findEntries(any(),any());clock.ms=600000;provider.lookup("sample",null);verify(db,times(2)).findEntries(any(),any());}
 @Test void oversizedResultsAreNeverCached(){successfulFixture("字".repeat(10000));provider.lookup("sample",null);provider.lookup("sample",null);assertThat(provider.cachedKeys()).isZero();verify(db,times(2)).findEntries(any(),any());}
 @Test void estimatedByteBudgetEvictsBeforeKeyLimit(){successfulFixture("字".repeat(1000));for(int i=0;i<800;i++)provider.lookup("term"+i,null);assertThat(provider.estimatedCacheBytes()).isLessThanOrEqualTo(16*1024*1024);assertThat(provider.cachedKeys()).isLessThan(800);}

}
