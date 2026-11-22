package com.readict.lexical.core;

import static com.readict.lexical.core.DictionaryJson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import com.readict.lexical.infrastructure.persistence.mapper.DictionaryMapper;
import com.readict.shared.persistence.typehandler.UuidBinaryTypeHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

@Service
public class DictionaryImporter {
    private final DictionaryMapper db;
    private final TransactionTemplate tx;
    public DictionaryImporter(DictionaryMapper db,PlatformTransactionManager transactions) {this.db=db;this.tx=new TransactionTemplate(transactions);}
    public static byte[] bytes(String uuid){return UuidBinaryTypeHandler.encode(UUID.fromString(uuid));}
    public static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    public Map<String,Object> inspect(String dataset) {
        var d=db.dataset(bytes(dataset));check(d!=null,"DATASET_NOT_FOUND");var a=db.active();Map<String,Object> r=new LinkedHashMap<>();r.put("datasetId",dataset);for(String k:List.of("status","import_phase","checkpoint_line","error_code"))r.put(k,d.get(k));r.put("entryCount",db.countEntries(bytes(dataset)));r.put("senseCount",db.countSenses(bytes(dataset)));r.put("formCount",db.countForms(bytes(dataset)));r.put("activeVersion",number(a,"version"));r.put("activeDatasetId",a.get("dataset_id")==null?null:UuidBinaryTypeHandler.decode((byte[])a.get("dataset_id")).toString());return r;
    }
    public Map<String,Object> activate(String dataset,long expected,boolean allow) {
        return tx.execute(s->{var a=db.lockActive();var d=db.lockDataset(bytes(dataset));check(d!=null&&"READY".equals(d.get("status")),"DATASET_NOT_READY");var m=parse((String)d.get("manifest"));check(allow&&m.path("usageProfile").asText().equals("LOCAL_MVP")&&m.path("quality").path("localMvpEligible").asBoolean(),"LOCAL_MVP_OPT_IN_REQUIRED");check(number(a,"version")==expected,"ACTIVE_VERSION_CONFLICT");if(!Arrays.equals((byte[])a.get("dataset_id"),bytes(dataset)))check(db.activate(bytes(dataset),expected)==1,"ACTIVE_VERSION_CONFLICT");return Map.of("datasetId",dataset,"activeVersion",number(db.active(),"version"),"usageProfile","LOCAL_MVP");});
    }
    public Map<String,Object> importBundle(BundleValidator.Validated bundle,boolean resume) {
        JsonNode m=bundle.manifest();String dataset=m.path("datasetId").asText();byte[] id=bytes(dataset);Map<String,Object> owner=new HashMap<>();owner.put("id",id);owner.put("owner",UuidBinaryTypeHandler.encode(UUID.randomUUID()));
        boolean done=Boolean.TRUE.equals(tx.execute(s->{
            var release=db.release(m.path("source").path("sourceHash").asText(),m.path("datasetVersion").asText(),m.path("converterVersion").asText());check(release==null||Arrays.equals((byte[])release.get("id"),id),"DATASET_IDENTITY_CONFLICT");
            var d=db.lockDataset(id);if(d==null){var row=new HashMap<>(owner);row.put("identity",m.path("identityHash").asText());row.put("source",m.path("source").path("sourceHash").asText());row.put("version",m.path("datasetVersion").asText());row.put("converter",m.path("converterVersion").asText());row.put("content",m.path("contentHash").asText());row.put("manifest",json(m));db.create(row);return false;}
            check(m.path("identityHash").asText().equals(d.get("identity_hash"))&&m.path("contentHash").asText().equals(d.get("content_hash"))&&parse((String)d.get("manifest")).equals(m),"DATASET_IDENTITY_CONFLICT");
            if("READY".equals(d.get("status")))return true;check(resume,"RESUME_REQUIRED");check("IMPORTING".equals(d.get("status")),"DATASET_FAILED");check(db.acquire(owner)==1,"LEASE_BUSY");return false;
        }));if(done)return inspect(dataset);
        AtomicBoolean lost=new AtomicBoolean();var heartbeat=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"dictionary-lease");t.setDaemon(true);return t;});
        heartbeat.scheduleAtFixedRate(()->{try {if(db.heartbeat(owner)!=1)lost.set(true);}catch(RuntimeException e){lost.set(true);}},30,30,TimeUnit.SECONDS);
        try {
            var d=db.dataset(id);String phase=(String)d.get("import_phase");long checkpoint=number(d,"checkpoint_line");List<String> phases=List.of("ENTRIES","SENSES","FORMS");int begin=phases.indexOf(phase);
            if(begin>=0)for(int p=begin;p<3;p++) {
                final String current=phases.get(p);final String next=p==2?"VERIFY":phases.get(p+1);final long skip=p==begin?checkpoint:0;long[] seen={0},committed={skip},size={0};List<Map<String,Object>> batch=new ArrayList<>();
                BundleValidator.rows(bundle.root().resolve(BundleValidator.FILES.get(p)),n->{if(seen[0]++<skip)return;var row=parameters(n);long cost=parameterBytes(row);check(cost<=2*1024*1024,"PARAMETER_BUDGET");if(!batch.isEmpty()&&(batch.size()>=500||size[0]+cost>2*1024*1024)){commit(owner,current,batch,committed[0]+batch.size(),lost);committed[0]+=batch.size();batch.clear();size[0]=0;}batch.add(row);size[0]+=cost;});
                if(!batch.isEmpty()){commit(owner,current,batch,committed[0]+batch.size(),lost);committed[0]+=batch.size();}check(seen[0]>=skip,"CHECKPOINT_INVALID");var progress=new HashMap<>(owner);progress.put("phase",next);progress.put("line",0);tx.executeWithoutResult(s->{check(!lost.get()&&db.checkpoint(progress)==1,"LEASE_LOST");});
            }
            tx.executeWithoutResult(s->{check(!lost.get()&&db.heartbeat(owner)==1,"LEASE_LOST");check(db.countEntries(id)==m.path("counts").path("entryCount").asLong()&&db.countSenses(id)==m.path("counts").path("senseCount").asLong()&&db.countForms(id)==m.path("counts").path("formCount").asLong()&&db.emptyEntries(id)==0,"DATABASE_VERIFY_FAILED");check(db.ready(owner)==1,"LEASE_LOST");});
            return inspect(dataset);
        }catch(RuntimeException e){try{if(e instanceof DictionaryFailure f&&Set.of("DATABASE_VERIFY_FAILED","PARAMETER_BUDGET","CHECKPOINT_INVALID").contains(f.code)){var fail=new HashMap<>(owner);fail.put("error",f.code);db.failed(fail);}else db.relinquish(owner);}catch(RuntimeException ignored){/* Version check still protects a newer owner. */}throw e;}
        finally{heartbeat.shutdownNow();}
    }
    protected void afterBatchCommitted(String phase,long line) { /* Extension point for controlled transport-fault tests. */ }
    private void commit(Map<String,Object> owner,String phase,List<Map<String,Object>> batch,long line,AtomicBoolean lost){
        tx.executeWithoutResult(s->{var lock=db.lockDataset((byte[])owner.get("id"));check(lock!=null&&!lost.get()&&db.heartbeat(owner)==1,"LEASE_LOST");switch(phase){case "ENTRIES"->db.entries((byte[])owner.get("id"),batch);case "SENSES"->db.senses((byte[])owner.get("id"),batch);case "FORMS"->db.forms((byte[])owner.get("id"),batch);default->throw new DictionaryFailure("PHASE_INVALID");}var progress=new HashMap<>(owner);progress.put("phase",phase);progress.put("line",line);check(db.checkpoint(progress)==1,"LEASE_LOST");});
        afterBatchCommitted(phase,line);
    }
    private static long parameterBytes(Map<String,Object> row){long n=16;for(Object v:row.values())n+=v instanceof byte[] b?b.length:v==null?0:v instanceof Number?8:v.toString().getBytes(StandardCharsets.UTF_8).length;return n;}
    private static Map<String,Object> parameters(JsonNode n){Map<String,Object> row=new HashMap<>();row.put("json",json(n));if(n.has("entryKey")){row.put("id",bytes(n.path("entryId").asText()));for(String k:List.of("entryKey","headword","lemmaKey"))row.put(k,n.path(k).asText());for(String k:List.of("sourceOrdinal","sectionOrder","componentOrder"))row.put(k,n.path(k).asLong());}else if(n.has("senseId")){row.put("id",bytes(n.path("senseId").asText()));row.put("entry",bytes(n.path("entryId").asText()));row.put("providerOrder",n.path("providerOrder").asInt());row.put("pos",n.path("pos").asText());row.put("zh",n.path("zh").asText());}else{row.put("entry",bytes(n.path("entryId").asText()));for(String k:List.of("surfaceKey","relation"))row.put(k,n.path(k).asText());row.put("priority",n.path("priority").asInt());}return row;}
}
