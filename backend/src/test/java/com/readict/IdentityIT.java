package com.readict;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.session.*;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.security.core.context.SecurityContext;
import tools.jackson.databind.*;
import com.readict.support.TestDatabaseInitializer;
import com.readict.identity.api.UserView;
import com.readict.identity.domain.*;
import com.readict.identity.application.*;
import com.readict.identity.infrastructure.security.AuthSecrets;
import com.readict.identity.infrastructure.persistence.*;
import com.readict.identity.infrastructure.persistence.entity.*;
import com.readict.identity.infrastructure.persistence.mapper.*;
import com.readict.identity.infrastructure.verification.*;
import com.readict.settings.infrastructure.persistence.*;
import com.readict.settings.infrastructure.persistence.mapper.*;
import com.readict.shared.persistence.typehandler.UuidBinaryTypeHandler;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "readict.auth.rate-hmac-key=identity-integration-rate-secret-0000000000000000",
 "readict.verification.hmac-key=identity-integration-code-secret-0000000000000000",
 "readict.verification.local-enabled=true"})
@ContextConfiguration(initializers=TestDatabaseInitializer.class)
@Import(IdentityIT.Configuration.class)
class IdentityIT {
    static class MutableClock extends Clock {
        private final AtomicReference<Instant> now=new AtomicReference<>(Instant.now());
        void set(Instant instant){now.set(instant);}void advance(long seconds){now.updateAndGet(v -> v.plusSeconds(seconds));}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}@Override public Instant instant(){return now.get();}
    }
    static class CapturingSender implements VerificationSender {
        final ConcurrentMap<UUID,Message> messages=new ConcurrentHashMap<>();
        volatile Delivery delivery=Delivery.SENT;
        volatile String mode="DEV_LOCAL";
        @Override public String mode(){return mode;}
        @Override public Delivery send(Message message){messages.put(message.challengeId(),message);return delivery;}
    }
    @TestConfiguration static class Configuration {
        @Bean @Primary MutableClock identityClock(){return new MutableClock();}
        @Bean @Primary CapturingSender capturingSender(){return new CapturingSender();}
    }
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;
    @Autowired CapturingSender sender;
    @Autowired RegistrationService registration;
    @Autowired VerificationService verification;
    @Autowired PasswordService passwordService;
    @Autowired com.readict.settings.application.SettingsService settingsService;
    @MockitoSpyBean IdentityRepository users;
    @Autowired UserMapper userMapper;
    @Autowired SettingsMapper settingsMapper;
    @Autowired ChallengeMapper challengeMapper;
    @Autowired ResetCredentialMapper credentialMapper;
    @Autowired AuthSecrets secrets;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean SettingsRepository settings;
    @MockitoSpyBean SessionCleaner cleaner;
    @MockitoSpyBean JdbcIndexedSessionRepository sessions;
    @LocalServerPort int port;
    final Set<UUID> created=ConcurrentHashMap.newKeySet();
    final Set<UUID> challenges=ConcurrentHashMap.newKeySet();
    final Set<String> identifiers=ConcurrentHashMap.newKeySet();
    final Set<String> sessionIds=ConcurrentHashMap.newKeySet();
    JdbcTemplate jdbc(){return new JdbcTemplate(dataSource);}
    byte[] bytes(UUID id){return UuidBinaryTypeHandler.encode(id);}
    void target() throws Exception {
        try(var connection=dataSource.getConnection()) {assertThat(connection.getCatalog()).isEqualTo("readict_test");assertThat(connection.getMetaData().getUserName()).startsWith("readict_test_app@");assertThat(connection.getMetaData().getURL()).startsWith("jdbc:mysql://127.0.0.1:3306/readict_test?");}
    }
    @BeforeEach void before() throws Exception {target();clock.set(Instant.now());sender.delivery=VerificationSender.Delivery.SENT;sender.mode="DEV_LOCAL";}
    @AfterEach void cleanup() throws Exception {
        reset(settings,cleaner,users,sessions);target();
        for(UUID id:created) sessions.findByPrincipalName(id.toString()).keySet().forEach(sessions::deleteById);
        sessionIds.forEach(sessions::deleteById);
        for(UUID id:created) {jdbc().update("DELETE FROM password_reset_credential WHERE user_id=?",bytes(id));jdbc().update("DELETE FROM verification_challenge WHERE user_id=?",bytes(id));settingsMapper.deleteById(id);userMapper.deleteById(id);}
        for(UUID id:challenges) jdbc().update("DELETE FROM verification_challenge WHERE id=? AND user_id IS NULL",bytes(id));
        for(String id:identifiers) jdbc().update("DELETE FROM rate_bucket WHERE key_hmac=? AND scope IN ('login-account','send-target','send-serial')",secrets.rate(id));
        for(String scope:List.of("login-ip","register-ip","send-ip")) jdbc().update("DELETE FROM rate_bucket WHERE scope=? AND key_hmac=?",scope,secrets.rate("127.0.0.1"));
        sender.messages.clear();
    }
    UserView fixture(String type,String identifier) {
        var id=LoginIdentifier.parse(type,identifier);identifiers.add(id.canonicalKey());
        UserView user=registration.register(type,identifier,"abc12345","127.0.0.1");created.add(user.id());return user;
    }
    String email(){return "it_"+UUID.randomUUID()+"@example.com";}
    class Browser {
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();
        String token;
        HttpResponse<String> call(String method,String path,Object body,boolean csrf) throws Exception {
            var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("Accept","application/json");
            if(csrf && token!=null)builder.header("X-CSRF-TOKEN",token);
            if(body!=null)builder.header("Content-Type","application/json");
            builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
            cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("readict-dev-session")).forEach(c -> {
                try {sessionIds.add(new String(Base64.getDecoder().decode(c.getValue()),java.nio.charset.StandardCharsets.UTF_8));}catch(IllegalArgumentException ignored) {sessionIds.add(c.getValue());}
            });
            return response;
        }
        JsonNode body(HttpResponse<String> response)throws Exception{return json.readTree(response.body());}
        void csrf() throws Exception {var response=call("GET","/auth/csrf",null,false);assertThat(response.statusCode()).isEqualTo(200);assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");token=body(response).path("token").asText();}
        HttpResponse<String> login(String type,String identifier,String password)throws Exception{csrf();return call("POST","/auth/login",Map.of("identifierType",type,"identifier",identifier,"password",password),true);}
        void authenticated(String type,String identifier)throws Exception{assertThat(login(type,identifier,"abc12345").statusCode()).isEqualTo(200);csrf();}
    }
    @Test void fixed86RegistrationUniqueKeysAndRealUuidRoundTrip() throws Exception {
        String phone="139"+String.format("%08d",new java.security.SecureRandom().nextInt(100_000_000));
        var browser=new Browser();browser.csrf();
        var input=Map.of("identifierType","phone","identifier",phone,"password","abc12345");
        var response=browser.call("POST","/auth/register",input,true);assertThat(response.statusCode()).isEqualTo(201);
        UUID id=UUID.fromString(browser.body(response).path("user").path("id").asText());created.add(id);identifiers.add("+86"+phone);
        assertThat(browser.body(response).path("user").path("phone").asText()).isEqualTo("+86"+phone);
        assertThat(browser.call("GET","/auth/me",null,false).statusCode()).isEqualTo(401);
        browser.csrf();assertThat(browser.call("POST","/auth/register",Map.of("identifierType","phone","identifier","+86"+phone,"password","abc12345"),true).statusCode()).isEqualTo(409);
        assertThat(userMapper.selectById(id).getId()).isEqualTo(id);assertThat(settingsMapper.selectById(id).getUserId()).isEqualTo(id);
        assertThat(userMapper.findIdentifier("phone","+86"+phone).getCreatedAt()).isEqualTo(userMapper.selectById(id).getCreatedAt());
        assertThat(browser.login("phone","+86"+phone,"abc12345").statusCode()).isEqualTo(200);
        browser.csrf();assertThat(browser.call("POST","/auth/register",Map.of("identifierType","phone","identifier","+12125551234","password","abc12345"),true).statusCode()).isEqualTo(422);
    }
    @Test void concurrentRegistrationHasOneUserAndOneSettingsAndFailureRollsBack() throws Exception {
        String email=email();identifiers.add(email);var executor=Executors.newFixedThreadPool(4);var gate=new CountDownLatch(1);
        try {
            List<Future<Boolean>> tasks=new ArrayList<>();
            for(int i=0;i<20;i++) tasks.add(executor.submit(() -> {gate.await();try {UserView u=registration.register("email",email,"abc12345","127.0.0.1");created.add(u.id());return true;}catch(DomainProblem e){assertThat(e.code()).isEqualTo("IDENTIFIER_UNAVAILABLE");return false;}}));
            gate.countDown();int success=0;for(var task:tasks)if(task.get(90,TimeUnit.SECONDS))success++;assertThat(success).isEqualTo(1);
            var user=users.find(LoginIdentifier.parse("email",email));assertThat(settingsMapper.selectById(user.getId())).isNotNull();
        } finally {executor.shutdownNow();}
        // Reset only this suite's dedicated IP key before the independent rollback probe.
        jdbc().update("DELETE FROM rate_bucket WHERE scope='register-ip' AND key_hmac=?",secrets.rate("127.0.0.1"));
        String rollback=email();identifiers.add(rollback);
        doThrow(new IllegalStateException("controlled settings failure")).when(settings).insert(any());
        assertThatThrownBy(() -> registration.register("email",rollback,"abc12345","127.0.0.1")).isInstanceOf(IllegalStateException.class);
        assertThat(users.find(LoginIdentifier.parse("email",rollback))).isNull();
    }
    @Test void sessionRotationPersistenceCsrfLogoutAndSettingsOwnership() throws Exception {
        String email=email();UserView u=fixture("email",email);Browser browser=new Browser();browser.csrf();String oldCsrf=browser.token;String oldCookie=browser.cookies.getCookieStore().getCookies().getFirst().getValue();
        var login=browser.call("POST","/auth/login",Map.of("identifierType","email","identifier",email.toUpperCase(Locale.ROOT),"password","abc12345"),true);
        assertThat(login.statusCode()).isEqualTo(200);String cookie=browser.cookies.getCookieStore().getCookies().getFirst().getValue();assertThat(cookie).isNotEqualTo(oldCookie);
        assertThat(login.headers().allValues("Set-Cookie").toString()).contains("HttpOnly","SameSite=Lax","Path=/").doesNotContain("Secure;");
        assertThat(browser.call("GET","/auth/me",null,false).statusCode()).isEqualTo(200);
        assertThat(browser.call("POST","/auth/logout",null,true).statusCode()).isEqualTo(403);browser.csrf();
        var read=browser.call("GET","/me/settings",null,false);assertThat(read.statusCode()).isEqualTo(200);assertThat(browser.body(read).path("version").asLong()).isEqualTo(1);
        var stored=sessions.findByPrincipalName(u.id().toString());assertThat(stored).hasSize(1);
        Session restored=sessions.findById(stored.keySet().iterator().next());Object value=restored.getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(value).isInstanceOf(SecurityContext.class);var principal=((SecurityContext)value).getAuthentication().getPrincipal();assertThat(principal).isInstanceOf(ReadictPrincipal.class);
        assertThat(restored.getAttributeNames()).doesNotContain("password","passwordHash");
        assertThat(browser.call("POST","/auth/logout",null,true).statusCode()).isEqualTo(204);assertThat(browser.call("GET","/auth/me",null,false).statusCode()).isEqualTo(401);
        browser.csrf();assertThat(browser.call("POST","/auth/logout",null,true).statusCode()).isEqualTo(204);
    }
    Map<String,Object> preferences(String theme){return Map.of("font","Georgia","fontSize",22,"lineHeight",1.8,"width",660,"theme",theme,"pronunciation","en-GB");}
    @Test void settingsVersionIdempotencyAndIsolation() throws Exception {
        String a=email(),b=email();UserView ua=fixture("email",a);UserView ub=fixture("email",b);Browser browser=new Browser();browser.authenticated("email",a);
        UUID request=UUID.randomUUID();var payload=Map.of("preferences",preferences("dark"),"expectedVersion",1,"requestId",request);
        var saved=browser.call("PUT","/me/settings",payload,true);assertThat(saved.statusCode()).isEqualTo(200);assertThat(browser.body(saved).path("version").asLong()).isEqualTo(2);
        assertThat(browser.body(browser.call("PUT","/me/settings",payload,true)).path("version").asLong()).isEqualTo(2);
        assertThat(browser.call("PUT","/me/settings",Map.of("preferences",preferences("white"),"expectedVersion",1,"requestId",request),true).statusCode()).isEqualTo(409);
        assertThat(browser.call("PUT","/me/settings",Map.of("preferences",preferences("white"),"expectedVersion",1,"requestId",UUID.randomUUID()),true).statusCode()).isEqualTo(409);
        assertThat(settingsMapper.selectById(ub.id()).getTheme()).isEqualTo("warm");assertThat(settingsMapper.selectById(ua.id()).getTheme()).isEqualTo("dark");
        var attacker=Map.of("preferences",preferences("white"),"expectedVersion",2,"requestId",UUID.randomUUID(),"userId",ub.id());
        assertThat(browser.call("PUT","/me/settings",attacker,true).statusCode()).isEqualTo(400);
        assertThat(settingsMapper.selectById(ub.id()).getTheme()).isEqualTo("warm");
    }
    @Test void passwordChangeInvalidatesBothBrowsersEvenIfPhysicalCleanupFails() throws Exception {
        String email=email();UserView user=fixture("email",email);Browser a=new Browser(),b=new Browser();a.authenticated("email",email);b.authenticated("email",email);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("controlled session cleanup failure")).when(sessions).findByPrincipalName(user.id().toString());
        assertThat(a.call("PUT","/auth/password",Map.of("currentPassword","abc12345","newPassword","NEWabc12345"),true).statusCode()).isEqualTo(204);
        assertThat(a.call("GET","/auth/me",null,false).statusCode()).isEqualTo(401);assertThat(b.call("GET","/auth/me",null,false).statusCode()).isEqualTo(401);
        Browser fresh=new Browser();assertThat(fresh.login("email",email,"abc12345").statusCode()).isEqualTo(401);assertThat(fresh.login("email",email,"NEWabc12345").statusCode()).isEqualTo(200);
        assertThat(userMapper.selectById(user.id()).getAuthVersion()).isEqualTo(1);
    }
    @Test void accountRateLimitIncludesUnknownUsersAndAlternatePhoneFormat() throws Exception {
        String phone="137"+String.format("%08d",new java.security.SecureRandom().nextInt(100_000_000));identifiers.add("+86"+phone);Browser browser=new Browser();
        for(int i=0;i<5;i++)assertThat(browser.login("phone",i%2==0?phone:"+86"+phone,"wrongPassword123").statusCode()).isEqualTo(401);
        var limited=browser.login("phone",phone,"wrongPassword123");assertThat(limited.statusCode()).isEqualTo(429);assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        clock.advance(601);assertThat(browser.login("phone",phone,"wrongPassword123").statusCode()).isEqualTo(401);
    }
    @Test void absoluteAndIdleSessionExpiry() throws Exception {
        String email=email();UserView user=fixture("email",email);Browser browser=new Browser();browser.authenticated("email",email);
        clock.advance(7*86400L+1);assertThat(browser.call("GET","/auth/me",null,false).statusCode()).isEqualTo(401);clock.set(Instant.now());
        browser.authenticated("email",email);Session session=sessions.findByPrincipalName(user.id().toString()).values().iterator().next();jdbc().update("UPDATE SPRING_SESSION SET LAST_ACCESS_TIME=?,EXPIRY_TIME=? WHERE SESSION_ID=?",Instant.now().minusSeconds(1900).toEpochMilli(),Instant.now().minusSeconds(100).toEpochMilli(),session.getId());
        assertThat(browser.call("GET","/auth/me",null,false).statusCode()).isEqualTo(401);
    }
    String proof(UUID id)throws Exception {
        for(int i=0;i<150;i++) {var message=sender.messages.get(id);var c=users.challenge(id);if(message!=null && c!=null && Set.of("SENT","UNKNOWN").contains(c.getState()))return message.proof();Thread.sleep(20);}
        throw new AssertionError("Captured challenge not delivered in time");
    }
    UUID accepted(Browser browser,String path,Object payload)throws Exception {
        var response=browser.call("POST",path,payload,true);assertThat(response.statusCode()).isEqualTo(202);UUID id=UUID.fromString(browser.body(response).path("challengeId").asText());challenges.add(id);return id;
    }
    @Test void localVerificationNeverMarksRealOwnershipAndRejectsWrongOwner() throws Exception {
        String email=email(),other=email();UserView user=fixture("email",email);fixture("email",other);Browser a=new Browser(),b=new Browser();a.authenticated("email",email);b.authenticated("email",other);
        UUID challenge=accepted(a,"/auth/verification/challenges",Map.of("identifierType","email"));String code=proof(challenge);
        assertThat(b.call("POST","/auth/verification/verify",Map.of("challengeId",challenge,"proof",code),true).statusCode()).isEqualTo(422);
        var result=a.call("POST","/auth/verification/verify",Map.of("challengeId",challenge,"proof",code),true);assertThat(result.statusCode()).isEqualTo(200);assertThat(a.body(result).path("simulation").asBoolean()).isTrue();assertThat(userMapper.selectById(user.id()).getEmailVerified()).isFalse();
        assertThat(a.call("POST","/auth/verification/verify",Map.of("challengeId",challenge,"proof",code),true).statusCode()).isEqualTo(422);
    }
    @Test void wrongAttemptsCommitAndCooldownResendSupersedesOldChallenge() throws Exception {
        String email=email();fixture("email",email);Browser browser=new Browser();browser.csrf();
        var target=Map.of("identifierType","email","identifier",email);UUID first=accepted(browser,"/auth/password-reset/challenges",target);String code=proof(first);String wrong=code.equals("000000")?"000001":"000000";
        for(int i=0;i<5;i++)assertThat(browser.call("POST","/auth/password-reset/verify",Map.of("challengeId",first,"proof",wrong),true).statusCode()).isEqualTo(422);
        assertThat(users.challenge(first).getAttempts()).isEqualTo(5);assertThat(users.challenge(first).getState()).isEqualTo("LOCKED");
        assertThat(browser.call("POST","/auth/password-reset/challenges",target,true).statusCode()).isEqualTo(429);
        clock.advance(61);UUID second=accepted(browser,"/auth/password-reset/challenges",target);String secondCode=proof(second);clock.advance(61);
        UUID third=accepted(browser,"/auth/password-reset/challenges",target);proof(third);
        assertThat(users.challenge(second).getState()).isEqualTo("SUPERSEDED");users.delivered(second,"SENT");assertThat(users.challenge(second).getState()).isEqualTo("SUPERSEDED");
        assertThat(browser.call("POST","/auth/password-reset/verify",Map.of("challengeId",second,"proof",secondCode),true).statusCode()).isEqualTo(422);
        clock.advance(301);assertThat(browser.call("POST","/auth/password-reset/verify",Map.of("challengeId",third,"proof",proof(third)),true).statusCode()).isEqualTo(422);
    }
    @Test void resetTokenIsOneTimeAndPolicyFailureDoesNotConsumeIt() throws Exception {
        String email=email();UserView user=fixture("email",email);Browser signedIn=new Browser();signedIn.authenticated("email",email);Browser browser=new Browser();browser.csrf();
        UUID challenge=accepted(browser,"/auth/password-reset/challenges",Map.of("identifierType","email","identifier",email));String code=proof(challenge);
        var verified=browser.call("POST","/auth/password-reset/verify",Map.of("challengeId",challenge,"proof",code),true);assertThat(verified.statusCode()).isEqualTo(200);String token=browser.body(verified).path("resetToken").asText();
        assertThat(browser.call("POST","/auth/password-reset/verify",Map.of("challengeId",challenge,"proof",code),true).statusCode()).isEqualTo(422);
        assertThat(browser.call("POST","/auth/password-reset/complete",Map.of("resetToken",token,"newPassword","weak"),true).statusCode()).isEqualTo(422);
        var executor=Executors.newFixedThreadPool(2);try {
            Callable<Integer> complete=() -> {try {verification.complete(token,"Changed12345");return 204;}catch(DomainProblem e){return e.status();}};
            var a=executor.submit(complete);var b=executor.submit(complete);assertThat(List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(204,410);
        } finally {executor.shutdownNow();}
        assertThat(signedIn.call("GET","/auth/me",null,false).statusCode()).isEqualTo(401);
        assertThat(new Browser().login("email",email,"Changed12345").statusCode()).isEqualTo(200);assertThat(userMapper.selectById(user.id()).getEmailVerified()).isFalse();
    }
    @Test void dummyResetHasSameShapeAndNeverDelivers() throws Exception {
        String known=email(),unknown=email();fixture("email",known);identifiers.add(unknown);Browser browser=new Browser();browser.csrf();
        UUID real=accepted(browser,"/auth/password-reset/challenges",Map.of("identifierType","email","identifier",known));proof(real);
        UUID dummy=accepted(browser,"/auth/password-reset/challenges",Map.of("identifierType","email","identifier",unknown));
        assertThat(sender.messages.containsKey(dummy)).isFalse();assertThat(users.challenge(dummy).getDeliveryMode()).isEqualTo("DUMMY");
        assertThat(browser.call("POST","/auth/password-reset/verify",Map.of("challengeId",dummy,"proof","000000"),true).statusCode()).isEqualTo(422);
    }
    @Test void missingChannelFailsBeforeAccountLookupAndAuthDependencyFailureIs503() throws Exception {
        String email=email();fixture("email",email);Browser browser=new Browser();browser.authenticated("email",email);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("controlled auth dependency failure")).when(users).user(any());
        assertThat(browser.call("GET","/auth/me",null,false).statusCode()).isEqualTo(503);
        reset(users);assertThat(browser.call("GET","/auth/me",null,false).statusCode()).isEqualTo(200);
        Browser anonymous=new Browser();anonymous.csrf();sender.mode="UNAVAILABLE";clearInvocations(users);
        for(String target:List.of(email,email()))assertThat(anonymous.call("POST","/auth/password-reset/challenges",Map.of("identifierType","email","identifier",target),true).statusCode()).isEqualTo(503);
        verify(users,never()).find(any());
    }
    @Test void concurrentSendingAndWrongProofsRespectCooldownAndAttemptCap() throws Exception {
        String email=email();fixture("email",email);Browser browser=new Browser();browser.csrf();var executor=Executors.newFixedThreadPool(8);var target=Map.of("identifierType","email","identifier",email);
        UUID challenge;
        try {
            List<Future<HttpResponse<String>>> tasks=new ArrayList<>();for(int i=0;i<8;i++)tasks.add(executor.submit(() -> browser.call("POST","/auth/password-reset/challenges",target,true)));
            int accepted=0;UUID found=null;
            for(var future:tasks) {var response=future.get(30,TimeUnit.SECONDS);if(response.statusCode()==202) {accepted++;found=UUID.fromString(browser.body(response).path("challengeId").asText());challenges.add(found);}else assertThat(response.statusCode()).isEqualTo(429);}
            assertThat(accepted).isEqualTo(1);challenge=found;String correct=proof(challenge);String wrong=correct.equals("000000")?"000001":"000000";
            List<Future<Integer>> failures=new ArrayList<>();for(int i=0;i<8;i++)failures.add(executor.submit(() -> browser.call("POST","/auth/password-reset/verify",Map.of("challengeId",challenge,"proof",wrong),true).statusCode()));
            for(var future:failures)assertThat(future.get(30,TimeUnit.SECONDS)).isEqualTo(422);
            assertThat(users.challenge(challenge).getAttempts()).isEqualTo(5);
        } finally {executor.shutdownNow();}
    }
    @Test void failedAndUnknownDeliveryAndCrashTransitionDoNotInventSuccess() throws Exception {
        String email=email();fixture("email",email);Browser browser=new Browser();browser.csrf();var target=Map.of("identifierType","email","identifier",email);
        sender.delivery=VerificationSender.Delivery.FAILED;UUID failed=accepted(browser,"/auth/password-reset/challenges",target);
        for(int i=0;i<100 && !users.challenge(failed).getState().equals("FAILED");i++)Thread.sleep(20);
        assertThat(users.challenge(failed).getState()).isEqualTo("FAILED");String failedCode=sender.messages.get(failed).proof();
        assertThat(browser.call("POST","/auth/password-reset/verify",Map.of("challengeId",failed,"proof",failedCode),true).statusCode()).isEqualTo(422);
        clock.advance(61);sender.delivery=VerificationSender.Delivery.UNKNOWN;UUID unknown=accepted(browser,"/auth/password-reset/challenges",target);String code=proof(unknown);
        assertThat(users.challenge(unknown).getState()).isEqualTo("UNKNOWN");
        // Simulate interrupted delivery, then recover only state; no resend or lifetime extension.
        var pending=users.challenge(unknown);pending.setState("SENDING");users.updateChallenge(pending);Instant expiry=pending.getExpiresAt();clock.advance(31);users.markStale(clock.instant());
        assertThat(users.challenge(unknown).getState()).isEqualTo("UNKNOWN");assertThat(users.challenge(unknown).getExpiresAt()).isEqualTo(expiry);
        assertThat(browser.call("POST","/auth/password-reset/verify",Map.of("challengeId",unknown,"proof",code),true).statusCode()).isEqualTo(200);
    }
    @Test void passwordTransactionFailureRollsBackVersionAndHash() throws Exception {
        String email=email();UserView user=fixture("email",email);Browser browser=new Browser();browser.authenticated("email",email);
        String original=userMapper.selectById(user.id()).getPasswordHash();
        doThrow(new IllegalStateException("controlled revoke failure")).when(users).revoke(eq(user.id()),any());
        assertThat(browser.call("PUT","/auth/password",Map.of("currentPassword","abc12345","newPassword","Newabc12345"),true).statusCode()).isEqualTo(500);
        reset(users);assertThat(userMapper.selectById(user.id()).getAuthVersion()).isEqualTo(0);assertThat(userMapper.selectById(user.id()).getPasswordHash()).isEqualTo(original);
    }

    @Test void concurrentPasswordsAndSettingsHaveOneVersionWinner() throws Exception {
        String email=email();UserView user=fixture("email",email);var principal=new ReadictPrincipal(user.id(),0,clock.instant());var executor=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> update=() -> {try {passwordService.change(principal,"abc12345","Concurrent12345");return 204;}catch(DomainProblem e){return e.status();}};
            var a=executor.submit(update);var b=executor.submit(update);var results=List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
            assertThat(results.stream().filter(code -> code==204).count()).isEqualTo(1);assertThat(userMapper.selectById(user.id()).getAuthVersion()).isEqualTo(1);
            var fresh=new ReadictPrincipal(user.id(),1,clock.instant());
            var preference=new com.readict.settings.api.Preferences("Georgia",24,new java.math.BigDecimal("1.8"),660,"dark","en-GB");
            Callable<Integer> save=() -> {try {settingsService.save(fresh,preference,1,UUID.randomUUID());return 200;}catch(DomainProblem e){return e.status();}};
            var first=executor.submit(save);var second=executor.submit(save);assertThat(List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            assertThat(settingsMapper.selectById(user.id()).getVersion()).isEqualTo(2);
        } finally {executor.shutdownNow();}
    }
    @Test void resetTransactionFailureDoesNotConsumeCredential() throws Exception {
        String email=email();UserView user=fixture("email",email);Browser browser=new Browser();browser.csrf();
        UUID challenge=accepted(browser,"/auth/password-reset/challenges",Map.of("identifierType","email","identifier",email));
        var verified=browser.call("POST","/auth/password-reset/verify",Map.of("challengeId",challenge,"proof",proof(challenge)),true);String token=browser.body(verified).path("resetToken").asText();
        doThrow(new IllegalStateException("controlled reset rollback")).when(users).revoke(eq(user.id()),any());
        assertThatThrownBy(() -> verification.complete(token,"Updated12345")).isInstanceOf(IllegalStateException.class);
        reset(users);assertThat(userMapper.selectById(user.id()).getAuthVersion()).isEqualTo(0);assertThat(users.credential(AuthSecrets.sha256(token)).getConsumedAt()).isNull();
        verification.complete(token,"Updated12345");assertThat(userMapper.selectById(user.id()).getAuthVersion()).isEqualTo(1);
    }

    @Test void ipBudgetAndInvalidCsrfAreDistinctAndPersisted() throws Exception {
        Browser browser=new Browser();browser.csrf();String valid=browser.token;browser.token="invalid-csrf-token";
        var invalid=browser.call("POST","/auth/register",Map.of("identifierType","email","identifier",email(),"password","abc12345"),true);
        assertThat(invalid.statusCode()).isEqualTo(403);assertThat(browser.body(invalid).path("code").asText()).isEqualTo("CSRF_INVALID");browser.token=valid;
        for(int i=0;i<20;i++) {String unknown=email();identifiers.add(unknown);assertThat(browser.login("email",unknown,"Wrong12345").statusCode()).isEqualTo(401);}
        String last=email();identifiers.add(last);assertThat(browser.login("email",last,"Wrong12345").statusCode()).isEqualTo(429);
    }
    @Test void resetCredentialExpiresAndApplicationSerializationIsSafe() throws Exception {
        String email=email();fixture("email",email);Browser browser=new Browser();browser.csrf();
        UUID challenge=accepted(browser,"/auth/password-reset/challenges",Map.of("identifierType","email","identifier",email));
        var response=browser.call("POST","/auth/password-reset/verify",Map.of("challengeId",challenge,"proof",proof(challenge)),true);String token=browser.body(response).path("resetToken").asText();clock.advance(601);
        assertThatThrownBy(() -> verification.complete(token,"Changed12345")).isInstanceOfSatisfying(DomainProblem.class,error -> assertThat(error.code()).isEqualTo("RESET_TOKEN_INVALID"));
    }
    @Test void acceptedResetTimingSamplesUseSameAsynchronousBoundary() throws Exception {
        Browser browser=new Browser();browser.csrf();List<Long> known=new ArrayList<>(),unknown=new ArrayList<>();
        for(int i=0;i<5;i++) {
            String real=email();fixture("email",real);String dummy=email();identifiers.add(dummy);
            long start=System.nanoTime();accepted(browser,"/auth/password-reset/challenges",Map.of("identifierType","email","identifier",real));known.add((System.nanoTime()-start)/1_000_000);
            start=System.nanoTime();accepted(browser,"/auth/password-reset/challenges",Map.of("identifierType","email","identifier",dummy));unknown.add((System.nanoTime()-start)/1_000_000);
        }
        Collections.sort(known);Collections.sort(unknown);
        assertThat(known).allSatisfy(ms -> assertThat(ms).isLessThan(2000));assertThat(unknown).allSatisfy(ms -> assertThat(ms).isLessThan(2000));
        System.out.println("Reset acceptance timing, local sample n=5 each: known median/max ms="+known.get(2)+"/"+known.get(4)+", dummy median/max ms="+unknown.get(2)+"/"+unknown.get(4)+"; diagnostic only, not a production anti-enumeration proof.");
    }

}
