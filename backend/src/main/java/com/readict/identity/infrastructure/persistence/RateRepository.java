package com.readict.identity.infrastructure.persistence;

import java.time.Instant;
import org.springframework.stereotype.Repository;
import com.readict.identity.infrastructure.persistence.mapper.RateBucketMapper;

@Repository
public class RateRepository {
    private final RateBucketMapper mapper;
    public RateRepository(RateBucketMapper mapper) { this.mapper=mapper; }
    public int increment(String scope,byte[] key,Instant window,Instant expiry) { return mapper.increment(scope,key,window,expiry); }
    public int count(String scope,byte[] key,Instant window) { return mapper.count(scope,key,window); }
    public int release(String scope,byte[] key,Instant window) { return mapper.release(scope,key,window); }
    public int lockTarget(byte[] key,Instant expiry) { return mapper.lockTarget(key,expiry); }
    public int cleanup(Instant now) { return mapper.cleanup(now); }
}
