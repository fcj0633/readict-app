package com.readict.identity.application;

import java.time.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.readict.identity.infrastructure.persistence.RateRepository;
import com.readict.identity.infrastructure.security.AuthSecrets;
import com.readict.identity.domain.DomainProblem;

@Service
public class RateService {
    public record Bucket(String scope,byte[] key,Instant window,int seconds,int limit) {}
    private final RateRepository mapper;
    private final AuthSecrets secrets;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public RateService(RateRepository mapper,AuthSecrets secrets,Clock clock,PlatformTransactionManager manager) {
        this.mapper=mapper;this.secrets=secrets;this.clock=clock;
        transaction=new TransactionTemplate(manager);transaction.setPropagationBehavior(3);
    }
    public List<Bucket> login(String account,String ip) {
        var buckets=List.of(bucket("login-account",account,600,5),bucket("login-ip",ip,600,20));
        reserve(buckets);return buckets;
    }
    public void cleanup() { mapper.cleanup(clock.instant()); }
    public void registration(String ip) { reserve(List.of(bucket("register-ip",ip,3600,20))); }
    public Bucket bucket(String scope,String key,int seconds,int limit) {
        long now=clock.instant().getEpochSecond();return new Bucket(scope,secrets.rate(key),Instant.ofEpochSecond(now-now%seconds),seconds,limit);
    }
    /** Called within the sending transaction so target row lock also serializes cooldown checks. */
    public void serializeTarget(String target) { mapper.lockTarget(secrets.rate(target),clock.instant().plusSeconds(86400)); }
    public void take(Bucket b) {
        mapper.increment(b.scope(),b.key(),b.window(),b.window().plusSeconds(b.seconds()+86400L));
        if(mapper.count(b.scope(),b.key(),b.window())>b.limit()) {
            int retry=(int)Math.max(1,b.window().plusSeconds(b.seconds()).getEpochSecond()-clock.instant().getEpochSecond());
            throw new DomainProblem(429,"RATE_LIMITED","操作过于频繁，请稍后重试",retry);
        }
    }
    private void reserve(List<Bucket> buckets) { transaction.executeWithoutResult(s -> buckets.forEach(this::take)); }
    public void successfulLogin(List<Bucket> buckets) {
        // Release only this attempt; never erase other failures or reset the shared IP budget.
        transaction.executeWithoutResult(s -> buckets.forEach(b -> mapper.release(b.scope(),b.key(),b.window())));
    }
}
