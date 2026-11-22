package com.readict.lexical.audio;

import static org.assertj.core.api.Assertions.*;
import static com.readict.lexical.core.DictionaryJson.*;
import static com.readict.lexical.core.DictionaryImporter.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import com.readict.lexical.tool.AudioToolConfiguration;
import com.readict.lexical.infrastructure.persistence.mapper.AudioMapper;
import com.readict.support.TestDatabaseInitializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes=AudioToolConfiguration.class,webEnvironment=SpringBootTest.WebEnvironment.NONE)
@ContextConfiguration(initializers=TestDatabaseInitializer.class)
class AudioIT {
    static boolean cleanupBlocked;
    static final Path OWNED=temporary();
    static Path temporary(){try{return Files.createTempDirectory("readict-audio-it-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry r){r.add("readict.audio.storage-root",()->OWNED.resolve("readict_test").toString());}
    @Autowired AudioImporter importer;
    @Autowired AudioMapper db;
    @Autowired AudioStorage storage;
    @Autowired DataSource datasource;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationContext context;
    @TempDir Path tmp;
    Set<String> datasets=new LinkedHashSet<>();byte[] original,expectedActive;long expectedVersion;AudioTestLock lock;
    JdbcTemplate jdbc(){return new JdbcTemplate(datasource);}
    void target() throws Exception{try(var c=datasource.getConnection()){assertThat(c.getCatalog()).isEqualTo("readict_test");assertThat(c.getMetaData().getUserName()).startsWith("readict_test_app@");}}
    @BeforeEach void before() throws Exception{target();lock=new AudioTestLock(datasource);var active=db.active();original=(byte[])active.get("dataset_id");expectedActive=original;expectedVersion=number(active,"version");}
    @AfterEach void cleanup() throws Exception{
        try{target();new TransactionTemplate(transactions).executeWithoutResult(s->{
            var active=db.lockActive();assertThat(number(active,"version")).as("External change: stop cleanup").isEqualTo(expectedVersion);assertThat((byte[])active.get("dataset_id")).isEqualTo(expectedActive);
            if(!Arrays.equals(original,expectedActive))assertThat(db.activate(original,expectedVersion)).isEqualTo(1);
            for(String id:datasets){assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM dictionary_audio_active WHERE dataset_id=?",Long.class,bytes(id))).isZero();jdbc().update("DELETE FROM dictionary_audio_mapping WHERE dataset_id=?",bytes(id));jdbc().update("DELETE FROM dictionary_audio_asset WHERE dataset_id=?",bytes(id));jdbc().update("DELETE FROM dictionary_audio_dataset WHERE id=?",bytes(id));}
        });}catch(Exception|AssertionError e){cleanupBlocked=true;throw e;}finally{if(lock!=null)lock.close();}
    }
    @AfterAll static void cleanFiles() throws Exception{if(!cleanupBlocked)AudioFiles.deleteOwnedTree(OWNED);}
    AudioBundleValidator.Bundle bundle(int count) throws Exception {Path p=AudioFixtures.create(tmp.resolve(UUID.randomUUID().toString()),count);var b=new AudioBundleValidator().validate(p);datasets.add(b.manifest().path("datasetId").asText());return b;}
    void activate(String id){var result=importer.activate(id,expectedVersion);expectedVersion=((Number)result.get("activeVersion")).longValue();expectedActive=bytes(id);}
    @Test void realImportIsIdempotentAndDoesNotActivateAndOfflineHasNoUserBeans() throws Exception{
        var b=bundle(2);String id=b.manifest().path("datasetId").asText();var result=importer.importBundle(b,false);assertThat(result.get("status")).isEqualTo("READY");assertThat(result.get("assetCount")).isEqualTo(2L);assertThat((byte[])db.active().get("dataset_id")).isEqualTo(original);assertThat(importer.importBundle(b,false).get("mappingCount")).isEqualTo(2L);
        assertThat(context.getBeansOfType(org.springframework.security.web.SecurityFilterChain.class)).isEmpty();assertThat(context.getBeansOfType(org.springframework.session.SessionRepository.class)).isEmpty();assertThat(context.getBeansOfType(com.readict.identity.application.VerificationService.class)).isEmpty();
    }
    @Test void committedLostAcknowledgementResumesAtFiveHundred() throws Exception{
        var b=bundle(501);String id=b.manifest().path("datasetId").asText();var faulty=new AudioImporter(db,storage,transactions){@Override protected void afterBatchCommitted(int phase,long count){if(phase==0)throw new IllegalStateException("lost acknowledgement");}};
        assertThatThrownBy(()->faulty.importBundle(b,false)).hasMessage("lost acknowledgement");assertThat(db.countAssets(bytes(id))).isEqualTo(500);assertThatThrownBy(()->importer.importBundle(b,false)).hasMessage("AUDIO_RESUME_REQUIRED");assertThat(importer.importBundle(b,true).get("status")).isEqualTo("READY");assertThat(db.countAssets(bytes(id))).isEqualTo(501);
    }
    @Test void expiredLeaseCanBeTakenOverAndStaleOwnerIsFenced() throws Exception{
        var b=bundle(2);String id=b.manifest().path("datasetId").asText();
        var failing=new AudioImporter(db,storage,transactions){@Override protected void afterBatchCommitted(int phase,long count){throw new IllegalStateException("stop");}};
        assertThatThrownBy(()->failing.importBundle(b,false)).hasMessage("stop");byte[] old=bytes(UUID.randomUUID().toString());jdbc().update("UPDATE dictionary_audio_dataset SET lease_owner=?,lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?",old,bytes(id));
        jdbc().update("UPDATE dictionary_audio_dataset SET lease_until=TIMESTAMPADD(SECOND,120,UTC_TIMESTAMP(6)) WHERE id=?",bytes(id));
        assertThatThrownBy(()->importer.importBundle(b,true)).hasMessage("AUDIO_LEASE_BUSY");
        jdbc().update("UPDATE dictionary_audio_dataset SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?",bytes(id));
        assertThat(importer.importBundle(b,true).get("status")).isEqualTo("READY");assertThat(db.heartbeat(Map.of("id",bytes(id),"owner",old))).isZero();assertThat(db.ready(Map.of("id",bytes(id),"owner",old))).isZero();
    }
    @Test void activationConflictsAndRollbackKeepAudioDatasetIsolation() throws Exception{
        var a=bundle(1);var b=bundle(2);String first=a.manifest().path("datasetId").asText(),second=b.manifest().path("datasetId").asText();importer.importBundle(a,false);importer.importBundle(b,false);activate(first);long prior=expectedVersion;
        assertThat(importer.activate(first,prior).get("activeVersion")).isEqualTo(prior);activate(second);assertThatThrownBy(()->importer.activate(first,prior)).hasMessage("AUDIO_VERSION_CONFLICT");activate(first);
        var catalog=new AudioCatalog(db);assertThat(catalog.find("sample","col-normalize-v1").audioDatasetId()).isEqualTo(first);assertThat(catalog.find("sample","col-normalize-v1").status()).isEqualTo("available");activate(second);assertThat(catalog.find("sample","col-normalize-v1").status()).isEqualTo("ambiguous");
        assertThatThrownBy(()->jdbc().update("UPDATE dictionary_audio_mapping SET audio_id=(SELECT id FROM dictionary_audio_asset WHERE dataset_id=? LIMIT 1) WHERE dataset_id=?",bytes(first),bytes(second))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void simultaneousActivationHasExactlyOneWinner() throws Exception{
        var a=bundle(1);var b=bundle(1);String first=a.manifest().path("datasetId").asText(),second=b.manifest().path("datasetId").asText();importer.importBundle(a,false);importer.importBundle(b,false);
        long prior=expectedVersion;var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try{
            var attempts=new ArrayList<Future<Boolean>>();
            for(String id:List.of(first,second))attempts.add(pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);try{importer.activate(id,prior);return true;}catch(com.readict.lexical.core.DictionaryJson.DictionaryFailure e){assertThat(e.code).isEqualTo("AUDIO_VERSION_CONFLICT");return false;}}));
            int winners=0;for(var attempt:attempts)if(attempt.get(30,TimeUnit.SECONDS))winners++;
            var active=db.active();expectedActive=(byte[])active.get("dataset_id");expectedVersion=number(active,"version");
            assertThat(winners).isEqualTo(1);assertThat(expectedVersion).isEqualTo(prior+1);assertThat(Arrays.equals(expectedActive,bytes(first))||Arrays.equals(expectedActive,bytes(second))).isTrue();
        }finally{pool.shutdownNow();}
    }
    @Test void databaseWriteFailureRollsBackRowsAndCheckpointTogether() throws Exception{
        var b=bundle(2);String id=b.manifest().path("datasetId").asText();
        var proxy=org.mockito.Mockito.mock(AudioMapper.class,org.mockito.AdditionalAnswers.delegatesTo(db));
        org.mockito.Mockito.doAnswer(call->{db.assets(call.getArgument(0),call.getArgument(1));throw new IllegalStateException("before commit");})
            .when(proxy).assets(org.mockito.ArgumentMatchers.any(byte[].class),org.mockito.ArgumentMatchers.anyList());
        var faulty=new AudioImporter(proxy,storage,transactions);
        assertThatThrownBy(()->faulty.importBundle(b,false)).hasMessage("before commit");
        assertThat(db.countAssets(bytes(id))).isZero();assertThat(number(db.dataset(bytes(id)),"imported_assets")).isZero();
        assertThat(importer.importBundle(b,true).get("status")).isEqualTo("READY");
    }
    @Test void parameterByteBudgetSplitsBatchesBelowRowLimit() throws Exception{
        Path root=AudioFixtures.create(tmp.resolve("large-metadata"),12);
        var lines=Files.readAllLines(root.resolve("mappings.jsonl"));var expanded=new ArrayList<String>();
        for(String line:lines){var n=parse(line);((tools.jackson.databind.node.ObjectNode)n.path("evidence")).put("diagnosticPadding","x".repeat(400000));expanded.add(json(n));}
        Files.writeString(root.resolve("mappings.jsonl"),String.join("\n",expanded)+"\n");
        var manifest=AudioFiles.object(root.resolve("manifest.json"));
        for(var f:manifest.path("files"))if(f.path("name").asText().equals("mappings.jsonl")){
            ((tools.jackson.databind.node.ObjectNode)f).put("sizeBytes",Files.size(root.resolve("mappings.jsonl"))).put("sha256",AudioFiles.digestFile(root.resolve("mappings.jsonl")));
        }
        Files.writeString(root.resolve("manifest.json"),json(manifest)+"\n");
        var b=new AudioBundleValidator().validate(root);datasets.add(b.manifest().path("datasetId").asText());
        var checkpoints=new ArrayList<Long>();var measured=new AudioImporter(db,storage,transactions){@Override protected void afterBatchCommitted(int phase,long count){if(phase==1)checkpoints.add(count);}};
        assertThat(measured.importBundle(b,false).get("status")).isEqualTo("READY");assertThat(checkpoints).containsExactly(5L,10L,12L);
    }
    @Test void wrongEnvironmentRootIsRejected() throws Exception{
        var env=new org.springframework.mock.env.MockEnvironment();env.setActiveProfiles("test");assertThatThrownBy(()->new AudioStorage(tmp.resolve("readict_dev").toString(),env).root()).hasMessage("AUDIO_STORAGE_ENVIRONMENT_INVALID");
    }
}
