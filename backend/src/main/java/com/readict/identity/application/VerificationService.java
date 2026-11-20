package com.readict.identity.application;

import java.time.*;
import java.util.*;
import java.security.*;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.readict.identity.domain.*;
import com.readict.identity.infrastructure.security.AuthSecrets;
import com.readict.identity.infrastructure.persistence.IdentityRepository;
import com.readict.identity.infrastructure.persistence.entity.*;
import com.readict.identity.infrastructure.verification.VerificationSender;

@Service
public class VerificationService {
    public record Accepted(UUID challengeId,int expiresIn,int retryAfter,Boolean simulation,String message) {}
    public record ProofResult(Boolean emailVerified,Boolean phoneVerified,String resetToken,Instant expiresAt,boolean simulation) {}
    private record Outcome(ProofResult value,DomainProblem error) {}
    private final IdentityRepository users;private final RateService rates;private final AuthSecrets secrets;private final Clock clock;private final VerificationSender sender;
    private final PasswordEncoder passwords;private final SessionCleaner cleaner;private final TransactionTemplate transaction;private final boolean localAllowed;private final boolean maintenanceEnabled;
    private final SecureRandom random=new SecureRandom();
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r -> {Thread t=new Thread(r,"verification-delivery");t.setDaemon(true);return t;});
    private final Semaphore slots=new Semaphore(20);
    public VerificationService(IdentityRepository users,RateService rates,AuthSecrets secrets,Clock clock,VerificationSender sender,PasswordEncoder passwords,SessionCleaner cleaner,Environment env,PlatformTransactionManager manager) {
        this.users=users;this.rates=rates;this.secrets=secrets;this.clock=clock;this.sender=sender;this.passwords=passwords;this.cleaner=cleaner;transaction=new TransactionTemplate(manager);
        maintenanceEnabled=!Arrays.asList(env.getActiveProfiles()).contains("test");
        localAllowed=env.getProperty("readict.verification.local-enabled",Boolean.class,false) && Arrays.stream(env.getActiveProfiles()).anyMatch(p -> p.equals("dev") || p.equals("test"));
    }
    private void available() {
        if(sender.mode().equals("UNAVAILABLE") || sender.mode().equals("DEV_LOCAL") && !localAllowed) throw unavailable();
        secrets.verification("configuration-probe");
    }
    public Accepted requestContact(ReadictPrincipal principal,String type,String ip) {
        available();var user=users.user(principal.userId());
        if(user==null || user.getAuthVersion()!=principal.authVersion()) throw new DomainProblem(401,"SESSION_EXPIRED","请重新登录");
        String target=type!=null && type.equals("email")?user.getEmailKey():type!=null && type.equals("phone")?user.getPhoneE164():null;
        if(target==null) throw new DomainProblem(422,"IDENTIFIER_INVALID","当前账号未绑定该联系方式");
        return request(LoginIdentifier.parse(type,target),"VERIFY_CONTACT",user,ip);
    }
    public Accepted requestReset(String type,String identifier,String ip) {
        available();LoginIdentifier id=LoginIdentifier.parse(type,identifier);
        // Queue capacity is reserved before the account lookup to avoid account-dependent availability.
        if(!slots.tryAcquire()) throw unavailable();
        try {return createAndDispatch(id,"PASSWORD_RESET",users.find(id),ip);}
        catch(RuntimeException ex) { slots.release();throw ex; }
    }
    private Accepted request(LoginIdentifier id,String purpose,UserEntity user,String ip) {
        if(!slots.tryAcquire()) throw unavailable();
        try { return createAndDispatch(id,purpose,user,ip); }
        catch(RuntimeException ex) { slots.release();throw ex; }
    }
    private Accepted createAndDispatch(LoginIdentifier id,String purpose,UserEntity user,String ip) {
        String proof=String.format(Locale.ROOT,"%06d",random.nextInt(1_000_000));
        var targetBucket=rates.bucket("send-target",id.canonicalKey(),3600,5);
        var ipBucket=rates.bucket("send-ip",ip,3600,20);
        ChallengeEntity challenge=transaction.execute(status -> {
            rates.serializeTarget(id.canonicalKey());rates.take(targetBucket);rates.take(ipBucket);
            Instant now=clock.instant();byte[] target=secrets.rate(id.canonicalKey());
            var latest=users.latest(target,purpose);
            if(latest!=null && latest.getNextSendAt().isAfter(now)) throw new DomainProblem(429,"RATE_LIMITED","请稍后重新获取验证码",(int)Math.max(1,Duration.between(now,latest.getNextSendAt()).getSeconds()));
            if(latest!=null && Set.of("SENDING","SENT","UNKNOWN").contains(latest.getState())) {latest.setState("SUPERSEDED");users.updateChallenge(latest);}
            var c=new ChallengeEntity();c.setId(UUID.randomUUID());c.setUserId(user==null?null:user.getId());c.setPurpose(purpose);c.setIdentifierType(id.type());c.setTargetKeyHmac(target);
            c.setDeliveryMode(user==null?"DUMMY":sender.mode());c.setMacKeyVersion(1);c.setState("SENDING");c.setGeneration(latest==null?1:latest.getGeneration()+1);c.setAttempts(0);c.setMaxAttempts(5);c.setAuthVersion(user==null?null:user.getAuthVersion());c.setCreatedAt(now);c.setExpiresAt(now.plusSeconds(300));c.setNextSendAt(now.plusSeconds(60));
            c.setCodeMac(user==null?null:mac(c,proof));users.insertChallenge(c);return c;
        });
        try {
            executor.execute(() -> {
                try {
                    if(user==null) {users.delivered(challenge.getId(),"SENT");return;}
                    var current=users.user(user.getId());
                    String target=current==null?null:challenge.getIdentifierType().equals("email")?current.getEmailKey():current.getPhoneE164();
                    if(current==null || !"ACTIVE".equals(current.getStatus()) || current.getAuthVersion()!=challenge.getAuthVersion() || target==null || !MessageDigest.isEqual(secrets.rate(target),challenge.getTargetKeyHmac())) {users.delivered(challenge.getId(),"FAILED");return;}
                    VerificationSender.Delivery delivery=sender.send(new VerificationSender.Message(challenge.getId(),target,proof));
                    users.delivered(challenge.getId(),delivery.name());
                } catch(RuntimeException ex) { try {users.delivered(challenge.getId(),"UNKNOWN");} catch(RuntimeException ignored) { /* Scheduled stale transition recovers SENDING. */ } }
                finally {slots.release();}
            });
        } catch(RejectedExecutionException ex) { slots.release();users.delivered(challenge.getId(),"UNKNOWN"); }
        return new Accepted(challenge.getId(),300,60,purpose.equals("VERIFY_CONTACT")?sender.mode().equals("DEV_LOCAL"):null,"若账号可用，请查收；可在冷却后重试");
    }
    private byte[] mac(ChallengeEntity c,String proof) { return secrets.verification(c.getId()+"|"+c.getPurpose()+"|"+c.getGeneration()+"|"+proof); }
    public ProofResult verify(UUID challengeId,String proof,String purpose,ReadictPrincipal principal) {
        available();
        if(challengeId==null || proof==null || !proof.matches("[0-9]{6}")) throw invalid();
        var snapshot=users.challenge(challengeId);
        if(snapshot==null) {MessageDigest.isEqual(secrets.verification(proof),new byte[32]);throw invalid();}
        Outcome result=transaction.execute(status -> {
            var user=snapshot.getUserId()==null?null:users.lockUser(snapshot.getUserId());
            var c=users.lockChallenge(challengeId);
            if(c==null || !purpose.equals(c.getPurpose()) || principal!=null && !principal.userId().equals(c.getUserId())) return new Outcome(null,invalid());
            Instant now=clock.instant();
            if(!Set.of("SENT","UNKNOWN").contains(c.getState())) return new Outcome(null,invalid());
            if(!now.isBefore(c.getExpiresAt())) {c.setState("EXPIRED");users.updateChallenge(c);return new Outcome(null,invalid());}
            if(!Set.of("SENT","UNKNOWN").contains(c.getState()) || c.getAttempts()>=c.getMaxAttempts()) return new Outcome(null,invalid());
            if(c.getDeliveryMode().equals("DEV_LOCAL") && !localAllowed) return new Outcome(null,invalid());
            boolean match=MessageDigest.isEqual(mac(c,proof),c.getCodeMac()==null?new byte[32]:c.getCodeMac());
            String target=user==null?null:c.getIdentifierType().equals("email")?user.getEmailKey():user.getPhoneE164();
            if(user==null || !"ACTIVE".equals(user.getStatus()) || user.getAuthVersion()!=c.getAuthVersion() || principal!=null && principal.authVersion()!=user.getAuthVersion() || target==null || !MessageDigest.isEqual(secrets.rate(target),c.getTargetKeyHmac())) match=false;
            if(!match) {c.setAttempts(c.getAttempts()+1);if(c.getAttempts()>=c.getMaxAttempts())c.setState("LOCKED");users.updateChallenge(c);return new Outcome(null,invalid());}
            boolean simulation=c.getDeliveryMode().equals("DEV_LOCAL");
            if(!simulation) throw new IllegalStateException("真实通道尚未实现");
            if(purpose.equals("VERIFY_CONTACT")) {
                c.setState("VERIFIED");c.setVerifiedAt(now);users.updateChallenge(c);
                return new Outcome(new ProofResult(user.getEmailVerified(),user.getPhoneVerified(),null,null,true),null);
            }
            byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            var credential=new ResetCredentialEntity();credential.setId(UUID.randomUUID());credential.setUserId(user.getId());credential.setChallengeId(c.getId());credential.setTokenHash(AuthSecrets.sha256(token));credential.setAuthVersion(user.getAuthVersion());credential.setProofMode(c.getDeliveryMode());credential.setCreatedAt(now);credential.setExpiresAt(now.plusSeconds(600));users.insertCredential(credential);
            c.setState("CONSUMED");c.setConsumedAt(now);users.updateChallenge(c);
            return new Outcome(new ProofResult(null,null,token,credential.getExpiresAt(),true),null);
        });
        if(result.error()!=null) throw result.error();return result.value();
    }
    public void complete(String token,String replacement) {
        available();if(token==null || !token.matches("[A-Za-z0-9_-]{43}"))throw tokenInvalid();
        var snapshot=users.credential(AuthSecrets.sha256(token));
        if(snapshot==null || snapshot.getConsumedAt()!=null || !clock.instant().isBefore(snapshot.getExpiresAt()) || !localAllowed || !snapshot.getProofMode().equals("DEV_LOCAL"))throw tokenInvalid();
        PasswordPolicy.validate(replacement);String hash=passwords.encode(replacement);
        transaction.executeWithoutResult(status -> {
            var user=users.lockUser(snapshot.getUserId());var credential=users.lockCredential(snapshot.getId());Instant now=clock.instant();
            if(user==null || !"ACTIVE".equals(user.getStatus()) || credential==null || credential.getConsumedAt()!=null || !now.isBefore(credential.getExpiresAt()) || credential.getAuthVersion()!=user.getAuthVersion() || !credential.getProofMode().equals("DEV_LOCAL"))throw tokenInvalid();
            if(users.changePassword(user.getId(),hash,credential.getAuthVersion(),now)!=1)throw tokenInvalid();
            credential.setConsumedAt(now);users.updateCredential(credential);users.revoke(user.getId(),now);
        });
        cleaner.stale(snapshot.getUserId());
    }
    @Scheduled(fixedDelay=30000) public void maintenance() {
        if(!maintenanceEnabled)return;
        try {users.markStale(clock.instant());users.cleanup(clock.instant().minusSeconds(86400));rates.cleanup();if(sender instanceof com.readict.identity.infrastructure.verification.LocalOutboxSender local)local.cleanup();}
        catch(RuntimeException ex) {org.slf4j.LoggerFactory.getLogger(getClass()).warn("code=VERIFICATION_MAINTENANCE_RETRY");}
    }
    @PreDestroy public void close() {executor.shutdownNow();}
    private static DomainProblem invalid(){return new DomainProblem(422,"CHALLENGE_INVALID","验证码无效或已过期");}
    private static DomainProblem tokenInvalid(){return new DomainProblem(410,"RESET_TOKEN_INVALID","恢复凭证无效或已过期");}
    private static DomainProblem unavailable(){return new DomainProblem(503,"VERIFICATION_UNAVAILABLE","验证通道暂不可用");}
}
