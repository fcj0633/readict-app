package com.readict.identity.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import com.readict.identity.domain.LoginIdentifier;
import com.readict.identity.infrastructure.persistence.entity.*;
import com.readict.identity.infrastructure.persistence.mapper.*;

@Repository
public class IdentityRepository {
    private final UserMapper users;
    private final ChallengeMapper challenges;
    private final ResetCredentialMapper credentials;
    public IdentityRepository(UserMapper users,ChallengeMapper challenges,ResetCredentialMapper credentials) { this.users=users;this.challenges=challenges;this.credentials=credentials; }
    public UserEntity find(LoginIdentifier id) { return users.findIdentifier(id.type(),id.canonicalKey()); }
    public UserEntity user(UUID id) { return users.selectById(id); }
    public UserEntity lockUser(UUID id) { return users.lockUser(id); }
    public void insertUser(UserEntity user) { users.insert(user); }
    public int changePassword(UUID id,String hash,long version,Instant now) { return users.changePassword(id,hash,version,now); }
    public ChallengeEntity challenge(UUID id) { return challenges.selectById(id); }
    public ChallengeEntity lockChallenge(UUID id) { return challenges.lockChallenge(id); }
    public ChallengeEntity latest(byte[] target,String purpose) { return challenges.latest(target,purpose); }
    public void insertChallenge(ChallengeEntity value) { challenges.insert(value); }
    public void updateChallenge(ChallengeEntity value) { challenges.updateById(value); }
    public void delivered(UUID id,String state) { challenges.delivered(id,state); }
    public ResetCredentialEntity credential(byte[] hash) { return credentials.findHash(hash); }
    public ResetCredentialEntity lockCredential(UUID id) { return credentials.lockCredential(id); }
    public void insertCredential(ResetCredentialEntity value) { credentials.insert(value); }
    public void updateCredential(ResetCredentialEntity value) { credentials.updateById(value); }
    public void revoke(UUID user,Instant now) { challenges.supersedeUser(user);credentials.revokeUser(user,now); }
    public void cleanup(Instant before) { credentials.cleanup(before);challenges.cleanup(before); }
    public void markStale(Instant now) { challenges.expireStale(now); }
}
