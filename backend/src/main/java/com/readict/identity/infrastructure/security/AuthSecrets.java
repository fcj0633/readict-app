package com.readict.identity.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AuthSecrets {
    private final byte[] rateKey;
    private final byte[] verificationKey;
    public AuthSecrets(Environment env) {
        String key=env.getProperty("readict.auth.rate-hmac-key", "");
        if(key.length()<32) throw new IllegalStateException("AUTH_RATE_HMAC_KEY 缺失或过短，认证未启用。");
        rateKey=key.getBytes(StandardCharsets.UTF_8);
        String verification=env.getProperty("readict.verification.hmac-key", "");
        verificationKey=verification.length()>=32 ? verification.getBytes(StandardCharsets.UTF_8) : null;
    }
    public byte[] rate(String text) { return hmac(rateKey,text); }
    public byte[] verification(String text) {
        if(verificationKey==null) throw new com.readict.identity.domain.DomainProblem(503,"VERIFICATION_UNAVAILABLE","验证通道暂不可用");
        return hmac(verificationKey,text);
    }
    public static byte[] sha256(String text) {
        try { return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA256 unavailable"); }
    }
    private static byte[] hmac(byte[] key,String text) {
        try { Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(text.getBytes(StandardCharsets.UTF_8)); }
        catch(java.security.GeneralSecurityException ex) { throw new IllegalStateException("HMAC unavailable"); }
    }
}
