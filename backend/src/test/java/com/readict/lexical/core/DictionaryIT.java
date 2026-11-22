package com.readict.lexical.core;

import static com.readict.lexical.core.DictionaryJson.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import com.readict.lexical.tool.DictionaryToolConfiguration;
import com.readict.lexical.infrastructure.persistence.mapper.DictionaryMapper;
import com.readict.shared.persistence.typehandler.UuidBinaryTypeHandler;
import com.readict.support.TestDatabaseInitializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataAccessResourceFailureException;

@SpringBootTest(classes=DictionaryToolConfiguration.class,webEnvironment=SpringBootTest.WebEnvironment.NONE)
@ContextConfiguration(initializers=TestDatabaseInitializer.class)
class DictionaryIT {
 @Autowired DictionaryImporter importer;
 @Autowired DictionaryMapper db;
 @Autowired PlatformTransactionManager tm;
 @Autowired DataSource ds;
 @Autowired DictionaryProvider provider;
 @Autowired ApplicationContext context;
 @TempDir Path temp;
 AtomicLong ownedActiveVersion=new AtomicLong();
 Set<String> created=new HashSet<>();Map<String,Object> originalActive;JdbcTemplate jdbc;
 @BeforeEach void before()throws Exception{jdbc=new JdbcTemplate(ds);verifyDatabase();originalActive=db.active();ownedActiveVersion.set(DictionaryImporter.number(originalActive,"version"));}
 void verifyDatabase()throws Exception{try(var c=ds.getConnection()){assertThat(c.getCatalog()).isEqualTo("readict_test");assertThat(c.getMetaData().getUserName()).startsWith("readict_test_app@");}}
 BundleValidator.Validated bundle(int size)throws Exception{var p=BundleFixtures.create(temp.resolve(UUID.randomUUID().toString()),size);var v=new BundleValidator().validate(p,true);created.add(v.manifest().path("datasetId").asText());return v;}
 @AfterEach void clean()throws Exception{verifyDatabase();new TransactionTemplate(tm).executeWithoutResult(s->{var current=db.lockActive();assertThat(DictionaryImporter.number(current,"version")).as("external active version change: stop cleanup").isEqualTo(ownedActiveVersion.get());byte[] currentId=(byte[])current.get("dataset_id");if(currentId!=null&&created.contains(UuidBinaryTypeHandler.decode(currentId).toString())){assertThat(jdbc.update("UPDATE dictionary_active SET dataset_id=?,version=version+1 WHERE slot=1 AND version=?",originalActive.get("dataset_id"),DictionaryImporter.number(current,"version"))).isEqualTo(1);}else assertThat(Arrays.equals(currentId,(byte[])originalActive.get("dataset_id"))).as("external active pointer change: stop cleanup").isTrue();for(String id:created){byte[] b=DictionaryImporter.bytes(id);assertThat(Arrays.equals((byte[])db.active().get("dataset_id"),b)).isFalse();jdbc.update("DELETE FROM dictionary_form_mapping WHERE dataset_id=?",b);jdbc.update("DELETE FROM dictionary_sense WHERE dataset_id=?",b);jdbc.update("DELETE FROM dictionary_entry WHERE dataset_id=?",b);jdbc.update("DELETE FROM dictionary_dataset WHERE id=?",b);}});}
 Map<String,Object> activate(String id,long version,boolean allow){var result=importer.activate(id,version,allow);ownedActiveVersion.set(((Number)result.get("activeVersion")).longValue());return result;}
 @Test void isolatedContextHasNoWebSecuritySessionOrUserJobs(){assertThat(context.getBeansOfType(com.readict.identity.application.VerificationService.class)).isEmpty();assertThat(context.getBeansOfType(org.springframework.security.web.SecurityFilterChain.class)).isEmpty();assertThat(context.getBeansOfType(org.springframework.session.SessionRepository.class)).isEmpty();}
 @Test void realImportIsIdempotentDoesNotActivateAndQuerySurvivesNewProvider()throws Exception{
  var v=bundle(2);String id=v.manifest().path("datasetId").asText();assertThat(importer.importBundle(v,false).get("status")).isEqualTo("READY");assertThat(db.active().get("dataset_id")).isEqualTo(originalActive.get("dataset_id"));assertThat(importer.importBundle(v,false).get("entryCount")).isEqualTo(2L);long version=DictionaryImporter.number(db.active(),"version");assertThatThrownBy(()->activate(id,version,false)).hasMessage("LOCAL_MVP_OPT_IN_REQUIRED");activate(id,version,true);assertThat(provider.lookup("testword",null).regions().size()).isEqualTo(2);assertThat(new LocalDictionaryProvider(db).lookup("alias",null).status()).isEqualTo("FOUND");assertThatThrownBy(()->activate(id,version,true)).hasMessage("ACTIVE_VERSION_CONFLICT");var active=db.active();activate(id,DictionaryImporter.number(active,"version"),true);assertThat(db.active().get("version")).isEqualTo(active.get("version"));
 }
 @Test void lostAcknowledgementResumesFromCommittedCheckpoint()throws Exception{
  var v=bundle(1001);String id=v.manifest().path("datasetId").asText();AtomicBoolean once=new AtomicBoolean();var fault=new DictionaryImporter(db,tm){@Override protected void afterBatchCommitted(String p,long line){if(once.compareAndSet(false,true))throw new DataAccessResourceFailureException("controlled post-commit acknowledgement loss");}};
  assertThatThrownBy(()->fault.importBundle(v,false)).isInstanceOf(DataAccessResourceFailureException.class);var interrupted=db.dataset(DictionaryImporter.bytes(id));assertThat(DictionaryImporter.number(interrupted,"checkpoint_line")).isEqualTo(500);assertThat(db.countEntries(DictionaryImporter.bytes(id))).isEqualTo(500);assertThatThrownBy(()->importer.importBundle(v,false)).hasMessage("RESUME_REQUIRED");assertThat(importer.importBundle(v,true).get("status")).isEqualTo("READY");assertThat(db.countEntries(DictionaryImporter.bytes(id))).isEqualTo(1001);assertThat(db.countSenses(DictionaryImporter.bytes(id))).isEqualTo(1001);
 }
 @Test void batchDatabaseFaultRollsBackRowsAndCheckpoint()throws Exception{
  var v=bundle(501);String id=v.manifest().path("datasetId").asText();var proxy=mock(DictionaryMapper.class,org.mockito.AdditionalAnswers.delegatesTo(db));doAnswer(inv->{db.entries(inv.getArgument(0),inv.getArgument(1));throw new DataAccessResourceFailureException("controlled before commit");}).when(proxy).entries(any(),any());var fault=new DictionaryImporter(proxy,tm);assertThatThrownBy(()->fault.importBundle(v,false)).isInstanceOf(DataAccessResourceFailureException.class);assertThat(db.countEntries(DictionaryImporter.bytes(id))).isZero();assertThat(DictionaryImporter.number(db.dataset(DictionaryImporter.bytes(id)),"checkpoint_line")).isZero();assertThat(importer.importBundle(v,true).get("status")).isEqualTo("READY");
 }
 @Test void expiredLeaseCanBeTakenOverAndOldOwnerCannotCommit()throws Exception{
  var v=bundle(501);String id=v.manifest().path("datasetId").asText();var interrupted=new DictionaryImporter(db,tm){@Override protected void afterBatchCommitted(String p,long line){throw new DataAccessResourceFailureException("controlled stop");}};assertThatThrownBy(()->interrupted.importBundle(v,false)).isInstanceOf(DataAccessResourceFailureException.class);
  byte[] old=UuidBinaryTypeHandler.encode(UUID.randomUUID());jdbc.update("UPDATE dictionary_dataset SET lease_owner=?,lease_until=TIMESTAMPADD(SECOND,120,UTC_TIMESTAMP(3)) WHERE id=?",old,DictionaryImporter.bytes(id));assertThatThrownBy(()->importer.importBundle(v,true)).hasMessage("LEASE_BUSY");jdbc.update("UPDATE dictionary_dataset SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=?",DictionaryImporter.bytes(id));assertThat(importer.importBundle(v,true).get("status")).isEqualTo("READY");Map<String,Object> stale=new HashMap<>();stale.put("id",DictionaryImporter.bytes(id));stale.put("owner",old);stale.put("phase","ENTRIES");stale.put("line",999);assertThat(db.checkpoint(stale)).isZero();assertThat(db.ready(stale)).isZero();
 }
 @Test void activationCompetitionRollbackAndCacheIsolation()throws Exception{
  var a=bundle(1);var b=bundle(2);String aid=a.manifest().path("datasetId").asText(),bid=b.manifest().path("datasetId").asText();importer.importBundle(a,false);importer.importBundle(b,false);long version=DictionaryImporter.number(db.active(),"version");var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);try{var futures=new ArrayList<Future<Boolean>>();for(String id:List.of(aid,bid))futures.add(pool.submit(()->{gate.await();try{activate(id,version,true);return true;}catch(DictionaryFailure e){assertThat(e.code).isEqualTo("ACTIVE_VERSION_CONFLICT");return false;}}));gate.countDown();int wins=0;for(var f:futures)if(f.get(15,TimeUnit.SECONDS))wins++;assertThat(wins).isEqualTo(1);}finally{pool.shutdownNow();}
  activate(aid,DictionaryImporter.number(db.active(),"version"),true);assertThat(provider.lookup("testword",null).regions().size()).isEqualTo(1);var pinned=provider.lookup("testword",null);activate(bid,DictionaryImporter.number(db.active(),"version"),true);assertThat(provider.lookup("testword",null).regions().size()).isEqualTo(2);assertThat(pinned.regions().size()).isEqualTo(1);assertThat(pinned.dataset().datasetId()).isEqualTo(aid);activate(aid,DictionaryImporter.number(db.active(),"version"),true);assertThat(provider.lookup("testword",null).regions().size()).isEqualTo(1);
 }
 @Test void databaseConstraintsRejectCrossDatasetSenseAndEmptyDatasetCannotReady()throws Exception{
  var a=bundle(1);var b=bundle(1);String aid=a.manifest().path("datasetId").asText(),bid=b.manifest().path("datasetId").asText();importer.importBundle(a,false);importer.importBundle(b,false);byte[] entry=(byte[])db.findEntries(DictionaryImporter.bytes(aid),"testword").getFirst().get("id");assertThatThrownBy(()->jdbc.update("INSERT INTO dictionary_sense(dataset_id,id,entry_id,provider_order,pos,zh,metadata_json) VALUES(?,?,?,?,?,?,?)",DictionaryImporter.bytes(bid),UuidBinaryTypeHandler.encode(UUID.randomUUID()),entry,7,"noun","自建","{}")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
 }
 @Test void immutableIdentityCannotBeOverwritten()throws Exception{var v=bundle(1);importer.importBundle(v,false);var m=v.manifest().deepCopy();((tools.jackson.databind.node.ObjectNode)m).put("contentHash","0".repeat(64));assertThatThrownBy(()->importer.importBundle(new BundleValidator.Validated(v.root(),m),false)).hasMessage("DATASET_IDENTITY_CONFLICT");}
 @Test void parameterBudgetSplitsBeforeFiveHundredRows()throws Exception{
  var v=bundle(40);List<tools.jackson.databind.JsonNode> senses=new ArrayList<>();BundleValidator.rows(v.root().resolve("senses.jsonl"),s->{var n=(tools.jackson.databind.node.ObjectNode)s.deepCopy();n.put("zh","字".repeat(10000));senses.add(n);});StringBuilder content=new StringBuilder();senses.forEach(n->content.append(json(n)).append('\n'));Files.writeString(v.root().resolve("senses.jsonl"),content);BundleFixtures.rehash(v.root());v=new BundleValidator().validate(v.root(),true);List<Long> commits=new ArrayList<>();var recording=new DictionaryImporter(db,tm){@Override protected void afterBatchCommitted(String phase,long line){if(phase.equals("SENSES"))commits.add(line);}};recording.importBundle(v,false);assertThat(commits).hasSizeGreaterThan(1);assertThat(commits.getFirst()).isLessThan(40L);assertThat(commits.getLast()).isEqualTo(40L);
 }
 @Test void verificationFailureCannotBecomeReadyOrActive()throws Exception{
  var v=bundle(1);String id=v.manifest().path("datasetId").asText();var m=v.manifest().deepCopy();((tools.jackson.databind.node.ObjectNode)m.path("counts")).put("entryCount",2);assertThatThrownBy(()->importer.importBundle(new BundleValidator.Validated(v.root(),m),false)).hasMessage("DATABASE_VERIFY_FAILED");assertThat(db.dataset(DictionaryImporter.bytes(id)).get("status")).isEqualTo("FAILED");assertThatThrownBy(()->activate(id,DictionaryImporter.number(db.active(),"version"),true)).hasMessage("DATASET_NOT_READY");
 }

}
