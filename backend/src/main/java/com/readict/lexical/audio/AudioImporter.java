package com.readict.lexical.audio;

import static com.readict.lexical.core.DictionaryJson.*;
import static com.readict.lexical.core.DictionaryImporter.*;
import static com.readict.lexical.audio.AudioFiles.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.readict.lexical.infrastructure.persistence.mapper.AudioMapper;
import com.readict.shared.persistence.typehandler.UuidBinaryTypeHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

@Service
public class AudioImporter {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(AudioImporter.class);
    private final AudioMapper db;
    private final AudioStorage storage;
    private final TransactionTemplate tx;
    public AudioImporter(AudioMapper db,AudioStorage storage,PlatformTransactionManager transactions){this.db=db;this.storage=storage;tx=new TransactionTemplate(transactions);}
    public Map<String,Object> inspect(String dataset){
        var d=db.dataset(bytes(dataset));check(d!=null,"AUDIO_DATASET_NOT_FOUND");var active=db.active();var result=new LinkedHashMap<String,Object>();
        result.put("datasetId",dataset);for(String key:List.of("status","imported_assets","imported_mappings","error_code"))result.put(key,d.get(key));
        result.put("assetCount",db.countAssets(bytes(dataset)));result.put("mappingCount",db.countMappings(bytes(dataset)));result.put("activeVersion",number(active,"version"));result.put("activeDatasetId",active.get("dataset_id")==null?null:UuidBinaryTypeHandler.decode((byte[])active.get("dataset_id")).toString());return result;
    }
    public Map<String,Object> activate(String dataset,long expected){
        return activate(dataset,expected,false);
    }
    public Map<String,Object> activate(String dataset,long expected,boolean allowSubset){
        var snapshot=db.dataset(bytes(dataset));check(snapshot!=null&&"READY".equals(snapshot.get("status")),"AUDIO_DATASET_NOT_READY");var manifest=parse((String)snapshot.get("manifest"));
        check(!manifest.path("usageProfile").asText().equals("LOCAL_MVP")||allowSubset,"AUDIO_POLICY_NOT_ALLOWED");
        check(storage.configured(),"AUDIO_NOT_READY");
        return tx.execute(s->{var active=db.lockActive();var d=db.lockDataset(bytes(dataset));check(d!=null&&"READY".equals(d.get("status"))&&snapshot.get("manifest").equals(d.get("manifest")),"AUDIO_DATASET_NOT_READY");check(db.countAssets(bytes(dataset))==manifest.path("counts").path("assetCount").asLong()&&db.countMappings(bytes(dataset))==manifest.path("counts").path("mappingCount").asLong(),"AUDIO_DATABASE_VERIFY_FAILED");check(number(active,"version")==expected,"AUDIO_VERSION_CONFLICT");
            if(!Arrays.equals((byte[])active.get("dataset_id"),bytes(dataset)))check(db.activate(bytes(dataset),expected)==1,"AUDIO_VERSION_CONFLICT");
            return Map.of("datasetId",dataset,"activeVersion",number(db.active(),"version"));});
    }
    public Map<String,Object> importBundle(AudioBundleValidator.Bundle bundle,boolean resume) throws IOException {
        var m=bundle.manifest();String dataset=m.path("datasetId").asText();byte[] id=bytes(dataset);
        long start=System.nanoTime();var published=new java.util.concurrent.atomic.AtomicLong();
        var prior=db.dataset(id);
        if(prior!=null)check(parse((String)prior.get("manifest")).equals(m),"AUDIO_DATASET_IDENTITY_CONFLICT");
        if(prior!=null&&"READY".equals(prior.get("status")))return inspect(dataset);
        if(prior!=null)check(resume,"AUDIO_RESUME_REQUIRED");
        boolean publishedPreviously=resume&&prior!=null&&number(prior,"imported_assets")==m.path("counts").path("assetCount").asLong()&&number(prior,"imported_mappings")==m.path("counts").path("mappingCount").asLong();
        // File publication is idempotent and precedes DB references. No lease exists yet.
        if(!publishedPreviously){try(var io=new AudioIo()){rows(bundle.root().resolve("assets.jsonl"),a->{try{io.submit(()->{storage.publish(safe(bundle.mediaRoot(),a.path("storageKey").asText()),a.path("storageKey").asText(),a.path("outputSha256").asText(),a.path("bytes").asLong());long count=published.incrementAndGet();if(count%5000==0)LOG.info("audio dataset={} stage=MEDIA_PUBLISH count={} elapsedMs={}",dataset,count,(System.nanoTime()-start)/1000000);});}catch(IOException e){throw new DictionaryFailure("AUDIO_STORAGE_FAILED");}});}}
        LOG.info("audio dataset={} stage={} count={} elapsedMs={}",dataset,publishedPreviously?"MEDIA_REUSE":"MEDIA_PUBLISHED",publishedPreviously?m.path("counts").path("assetCount").asLong():published.get(),(System.nanoTime()-start)/1000000);
        var owner=new HashMap<String,Object>();owner.put("id",id);owner.put("owner",bytes(UUID.randomUUID().toString()));
        boolean done=Boolean.TRUE.equals(tx.execute(s->{
            var existing=db.lockDataset(id);
            if(existing==null){var create=new HashMap<>(owner);create.put("version",m.path("version").asText());create.put("identity",HexFormat.of().parseHex(m.path("identitySha256").asText()));create.put("manifestSha",HexFormat.of().parseHex(hash(m)));create.put("manifest",json(m));create.put("assets",m.path("counts").path("assetCount").asLong());create.put("mappings",m.path("counts").path("mappingCount").asLong());db.create(create);return false;}
            check(parse((String)existing.get("manifest")).equals(m),"AUDIO_DATASET_IDENTITY_CONFLICT");
            if("READY".equals(existing.get("status")))return true;
            check(resume,"AUDIO_RESUME_REQUIRED");check("IMPORTING".equals(existing.get("status")),"AUDIO_DATASET_FAILED");check(db.acquire(owner)==1,"AUDIO_LEASE_BUSY");return false;
        }));if(done)return inspect(dataset);
        AtomicBoolean lost=new AtomicBoolean();var heartbeat=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"audio-import-lease");t.setDaemon(true);return t;});
        heartbeat.scheduleAtFixedRate(()->{try{if(db.heartbeat(owner)!=1)lost.set(true);}catch(RuntimeException e){lost.set(true);}},30,30,TimeUnit.SECONDS);
        try{
            var row=db.dataset(id);long[] progress={number(row,"imported_assets"),number(row,"imported_mappings")};
            for(int phase=0;phase<2;phase++){
                final int stage=phase;long skip=progress[stage];long[] seen={0},cost={0};var batch=new ArrayList<Map<String,Object>>();
                rows(bundle.root().resolve(stage==0?"assets.jsonl":"mappings.jsonl"),n->{
                    if(seen[0]++<skip)return;Map<String,Object> item=parameters(n,stage);long bytes=16;
                    for(Object v:item.values())bytes+=v instanceof byte[] b?b.length:v instanceof Number?8:v.toString().getBytes(StandardCharsets.UTF_8).length;
                    check(bytes<=2097152,"AUDIO_PARAMETER_LIMIT");
                    if(!batch.isEmpty()&&(batch.size()>=500||cost[0]+bytes>2097152)){commit(owner,stage,batch,progress,lost);batch.clear();cost[0]=0;}
                    batch.add(item);cost[0]+=bytes;
                });
                if(!batch.isEmpty())commit(owner,stage,batch,progress,lost);
                check(seen[0]>=skip,"AUDIO_CHECKPOINT_INVALID");
            }
            // All media was hash-checked and atomically published before the dataset was created.
            // MVP trusts that immutable publication; playback checks missing/changed files on access.
            tx.executeWithoutResult(s->{db.lockDataset(id);check(!lost.get()&&db.heartbeat(owner)==1,"AUDIO_LEASE_LOST");check(db.countAssets(id)==m.path("counts").path("assetCount").asLong()&&db.countMappings(id)==m.path("counts").path("mappingCount").asLong(),"AUDIO_DATABASE_VERIFY_FAILED");check(db.ready(owner)==1,"AUDIO_LEASE_LOST");});LOG.info("audio dataset={} stage=READY assets={} mappings={} elapsedMs={}",dataset,progress[0],progress[1],(System.nanoTime()-start)/1000000);return inspect(dataset);
        }catch(RuntimeException|IOException e){try{if(e instanceof DictionaryFailure f&&Set.of("AUDIO_DATABASE_VERIFY_FAILED","AUDIO_MEDIA_INVALID","AUDIO_CHECKPOINT_INVALID").contains(f.code))db.failed(owner);else db.relinquish(owner);}catch(RuntimeException ignored){/* Never overwrite a newer owner. */}throw e;}
        finally{heartbeat.shutdownNow();}
    }
    protected void afterBatchCommitted(int stage,long count) { /* Transport fault injection for integration tests. */ }
    private void commit(Map<String,Object> owner,int stage,List<Map<String,Object>> rows,long[] progress,AtomicBoolean lost){
        long start=System.nanoTime();long next=progress[stage]+rows.size();
        tx.executeWithoutResult(s->{db.lockDataset((byte[])owner.get("id"));check(!lost.get()&&db.heartbeat(owner)==1,"AUDIO_LEASE_LOST");if(stage==0)db.assets((byte[])owner.get("id"),rows);else db.mappings((byte[])owner.get("id"),rows);var p=new HashMap<>(owner);p.put("assets",stage==0?next:progress[0]);p.put("mappings",stage==1?next:progress[1]);check(db.checkpoint(p)==1,"AUDIO_LEASE_LOST");});progress[stage]=next;LOG.info("audio dataset={} stage={} count={} elapsedMs={}",UuidBinaryTypeHandler.decode((byte[])owner.get("id")),stage==0?"ASSETS":"MAPPINGS",next,(System.nanoTime()-start)/1000000);afterBatchCommitted(stage,next);
    }
    private static Map<String,Object> parameters(JsonNode n,int stage){
        var r=new HashMap<String,Object>();r.put("ordinal",n.path("sourceOrdinal").asLong());
        if(stage==0){r.put("id",bytes(n.path("audioId").asText()));r.put("source",n.path("sourceResourceKey").asText());r.put("sourceSha",HexFormat.of().parseHex(n.path("sourceSha256").asText()));r.put("outputSha",HexFormat.of().parseHex(n.path("outputSha256").asText()));r.put("storage",n.path("storageKey").asText());r.put("bytes",n.path("bytes").asLong());r.put("duration",n.path("durationMs").asInt());}
        else{r.put("link",n.path("linkOrder").asInt());r.put("index",n.path("indexKey").asText());r.put("display",n.path("displayHeadword").asText());r.put("word",n.path("wordKey").asText());r.put("accent",n.path("accent").asText());r.put("audio",bytes(n.path("audioId").asText()));r.put("evidence",json(n.path("evidence")));}return r;
    }
}
