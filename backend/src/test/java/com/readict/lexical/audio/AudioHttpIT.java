package com.readict.lexical.audio;

import static org.assertj.core.api.Assertions.*;
import static com.readict.lexical.core.DictionaryJson.*;
import static com.readict.lexical.core.DictionaryImporter.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import com.readict.identity.application.RegistrationService;
import com.readict.identity.infrastructure.security.AuthSecrets;
import com.readict.identity.infrastructure.persistence.mapper.UserMapper;
import com.readict.settings.infrastructure.persistence.mapper.SettingsMapper;
import com.readict.lexical.infrastructure.persistence.mapper.AudioMapper;
import com.readict.support.TestDatabaseInitializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"readict.auth.rate-hmac-key=audio-http-integration-rate-secret-00000000000000","readict.verification.hmac-key=audio-http-integration-code-secret-00000000000000"})
@ContextConfiguration(initializers=TestDatabaseInitializer.class)
class AudioHttpIT {
    static boolean cleanupBlocked;
    static final Path OWNED=AudioIT.temporary();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("readict.audio.storage-root",()->OWNED.resolve("readict_test").toString());}
    @Autowired AudioImporter importer;
    @Autowired AudioStorage storage;
    @Autowired AudioMapper db;
    @Autowired DataSource datasource;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RegistrationService registration;
    @Autowired UserMapper users;
    @Autowired SettingsMapper settings;
    @Autowired AuthSecrets secrets;
    @Autowired JdbcIndexedSessionRepository sessions;
    @LocalServerPort int port;
    @TempDir Path tmp;
    Set<UUID> created=new HashSet<>();Set<String> identifiers=new HashSet<>();String dataset;byte[] original;long version;AudioTestLock lock;
    JdbcTemplate jdbc(){return new JdbcTemplate(datasource);}
    class Browser {
        HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String token,email;
        HttpResponse<byte[]> call(String method,String path,String body,String... headers) throws Exception {
            var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));for(int i=0;i<headers.length;i+=2)b.header(headers[i],headers[i+1]);if(body!=null)b.header("Content-Type","application/json");
            return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
        }
        void csrf() throws Exception{var r=call("GET","/api/v1/auth/csrf",null);assertThat(r.statusCode()).isEqualTo(200);token=parse(new String(r.body(),java.nio.charset.StandardCharsets.UTF_8)).path("token").asText();}
        UUID login() throws Exception{
            email="it_audio_"+UUID.randomUUID()+"@example.com";identifiers.add(email);var user=registration.register("email",email,"abc12345","127.0.0.17");created.add(user.id());csrf();
            assertThat(call("POST","/api/v1/auth/login",json(Map.of("identifierType","email","identifier",email,"password","abc12345")),"X-CSRF-TOKEN",token).statusCode()).isEqualTo(200);csrf();return user.id();
        }
    }
    @BeforeEach void setup(TestInfo info) throws Exception{
        try(var c=datasource.getConnection()){assertThat(c.getCatalog()).isEqualTo("readict_test");assertThat(c.getMetaData().getUserName()).startsWith("readict_test_app@");}
        lock=new AudioTestLock(datasource);var active=db.active();original=(byte[])active.get("dataset_id");version=number(active,"version");var bundle=new AudioBundleValidator().validate(AudioFixtures.create(tmp.resolve("bundle"),1,info.getTestMethod().orElseThrow().getName().equals("clientAbortClosesPrivateFileAndNextRequestStillWorks")?new byte[2097152]:null));dataset=bundle.manifest().path("datasetId").asText();importer.importBundle(bundle,false);version=((Number)importer.activate(dataset,version).get("activeVersion")).longValue();
    }
    @AfterEach void cleanup() throws Exception{
        try{try(var c=datasource.getConnection()){assertThat(c.getCatalog()).isEqualTo("readict_test");}
        new TransactionTemplate(transactions).executeWithoutResult(s->{var active=db.lockActive();assertThat(number(active,"version")).as("External change: stop cleanup").isEqualTo(version);assertThat((byte[])active.get("dataset_id")).isEqualTo(bytes(dataset));assertThat(db.activate(original,version)).isEqualTo(1);jdbc().update("DELETE FROM dictionary_audio_mapping WHERE dataset_id=?",bytes(dataset));jdbc().update("DELETE FROM dictionary_audio_asset WHERE dataset_id=?",bytes(dataset));jdbc().update("DELETE FROM dictionary_audio_dataset WHERE id=?",bytes(dataset));});
        for(UUID id:created){sessions.findByPrincipalName(id.toString()).keySet().forEach(sessions::deleteById);jdbc().update("DELETE FROM spring_session WHERE PRINCIPAL_NAME=?",id.toString());settings.deleteById(id);users.deleteById(id);}
        for(String identifier:identifiers)jdbc().update("DELETE FROM rate_bucket WHERE key_hmac=?",secrets.rate(identifier));jdbc().update("DELETE FROM rate_bucket WHERE scope='register-ip' AND key_hmac=?",secrets.rate("127.0.0.17"));}catch(Exception|AssertionError e){cleanupBlocked=true;throw e;}finally{if(lock!=null)lock.close();}
    }
    @AfterAll static void files() throws Exception{if(!cleanupBlocked)AudioFiles.deleteOwnedTree(OWNED);}
    @Test void authenticatedHttpServesExactRangesAndConditionalResponsesBeforeSessionRevocation() throws Exception{
        var a=AudioFiles.object(tmp.resolve("bundle/assets.jsonl"));String path="/api/v1/dictionary/audio/"+a.path("audioId").asText();byte[] media=Files.readAllBytes(tmp.resolve("bundle").resolve(a.path("storageKey").asText()));
        var anonymous=new Browser();assertThat(anonymous.call("GET",path,null).statusCode()).isEqualTo(401);
        var first=new Browser();UUID user=first.login();var second=new Browser();second.login();
        var full=first.call("GET",path,null);assertThat(full.statusCode()).isEqualTo(200);assertThat(full.body()).isEqualTo(media);String etag=full.headers().firstValue("ETag").orElseThrow();assertThat(full.headers().firstValue("Cache-Control").orElseThrow()).isEqualTo("private, no-cache");
        assertThat(second.call("GET",path,null).body()).isEqualTo(media);
        var partial=first.call("GET",path,null,"Range","bytes=2-5");assertThat(partial.statusCode()).isEqualTo(206);assertThat(partial.body()).isEqualTo(Arrays.copyOfRange(media,2,6));assertThat(partial.headers().firstValue("Content-Range").orElseThrow()).isEqualTo("bytes 2-5/"+media.length);
        var suffix=first.call("GET",path,null,"Range","bytes=-4");assertThat(suffix.body()).isEqualTo(Arrays.copyOfRange(media,media.length-4,media.length));
        assertThat(first.call("GET",path,null,"Range","bytes=2-").body()).isEqualTo(Arrays.copyOfRange(media,2,media.length));
        assertThat(first.call("GET",path,null,"Range","bytes=0-1,4-5").statusCode()).isEqualTo(416);
        assertThat(first.call("GET",path,null,"Range","bytes=999999-").headers().firstValue("Content-Range").orElseThrow()).isEqualTo("bytes */"+media.length);
        assertThat(first.call("GET",path,null,"Range","bytes=0-2","If-Range","\"other\"").statusCode()).isEqualTo(200);
        assertThat(first.call("GET",path,null,"Range","bytes=0-2","If-Range",etag).statusCode()).isEqualTo(206);
        assertThat(first.call("GET",path,null,"If-None-Match",etag).statusCode()).isEqualTo(304);
        var head=first.call("HEAD",path,null,"Range","bytes=2-5");assertThat(head.statusCode()).isEqualTo(200);assertThat(head.body()).isEmpty();assertThat(head.headers().firstValue("Content-Length").orElseThrow()).isEqualTo(String.valueOf(media.length));
        assertThat(first.call("HEAD",path,null,"If-None-Match",etag).statusCode()).isEqualTo(304);
        assertThat(first.call("GET","/api/v1/dictionary/audio/invalid",null).statusCode()).isEqualTo(400);
        assertThat(first.call("GET","/api/v1/dictionary/audio/"+UUID.randomUUID(),null).statusCode()).isEqualTo(404);
        // A stale conditional request must still authenticate after session expiry.
        var expired=new Browser();UUID expiredUser=expired.login();
        jdbc().update("UPDATE spring_session SET LAST_ACCESS_TIME=?,EXPIRY_TIME=? WHERE PRINCIPAL_NAME=?",Instant.now().minusSeconds(3600).toEpochMilli(),Instant.now().minusSeconds(1800).toEpochMilli(),expiredUser.toString());
        assertThat(expired.call("GET",path,null,"If-None-Match",etag).statusCode()).isEqualTo(401);
        Path published=storage.root().resolve(a.path("storageKey").asText());
        Files.delete(published);assertThat(second.call("GET",path,null).statusCode()).isEqualTo(404);
        byte[] changed=media.clone();changed[0]^=1;Files.write(published,changed);
        assertThat(second.call("GET",path,null,"If-None-Match",etag).statusCode()).isEqualTo(503);
        Files.write(published,media);
        var otherSession=new Browser();otherSession.csrf();
        assertThat(otherSession.call("POST","/api/v1/auth/login",json(Map.of("identifierType","email","identifier",first.email,"password","abc12345")),"X-CSRF-TOKEN",otherSession.token).statusCode()).isEqualTo(200);
        assertThat(first.call("PUT","/api/v1/auth/password",json(Map.of("currentPassword","abc12345","newPassword","abc123456")),"X-CSRF-TOKEN",first.token).statusCode()).isEqualTo(204);
        assertThat(first.call("GET",path,null,"If-None-Match",etag).statusCode()).isEqualTo(401);
        assertThat(otherSession.call("GET",path,null,"If-None-Match",etag).statusCode()).isEqualTo(401);
        assertThat(second.call("POST","/api/v1/auth/logout",null,"X-CSRF-TOKEN",second.token).statusCode()).isEqualTo(204);assertThat(second.call("GET",path,null,"If-None-Match",etag).statusCode()).isEqualTo(401);
        assertThat(anonymous.call("HEAD",path,null,"If-None-Match",etag).statusCode()).isEqualTo(401);
    }
    @Test void clientAbortClosesPrivateFileAndNextRequestStillWorks() throws Exception{
        var a=AudioFiles.object(tmp.resolve("bundle/assets.jsonl"));String path="/api/v1/dictionary/audio/"+a.path("audioId").asText();
        var browser=new Browser();browser.login();
        var manager=(CookieManager)browser.client.cookieHandler().orElseThrow();String cookies=manager.getCookieStore().getCookies().stream().map(c->c.getName()+"="+c.getValue()).collect(java.util.stream.Collectors.joining("; "));
        try(var socket=new java.net.Socket("127.0.0.1",port)){
            socket.setSoTimeout(5000);socket.setSoLinger(true,0);
            socket.getOutputStream().write(("GET "+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\nCookie: "+cookies+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));socket.getOutputStream().flush();
            var input=socket.getInputStream();var header=new StringBuilder();while(!header.toString().endsWith("\r\n\r\n")){int value=input.read();assertThat(value).isNotEqualTo(-1);header.append((char)value);assertThat(header.length()).isLessThan(16384);}
            assertThat(header.toString()).startsWith("HTTP/1.1 200");assertThat(input.read()).isNotEqualTo(-1);
        }
        Path file=storage.root().resolve(a.path("storageKey").asText()),moved=file.resolveSibling("abort-release-check.tmp");
        java.io.IOException locked=null;for(int attempt=0;attempt<30;attempt++)try{Files.move(file,moved);locked=null;break;}catch(java.io.IOException e){locked=e;Thread.sleep(100);}
        if(locked!=null)throw locked;Files.move(moved,file);
        assertThat(browser.call("GET",path,null).statusCode()).isEqualTo(200);
    }

}
