package com.readict;

import java.time.*;
import java.util.*;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import com.readict.support.*;
import com.readict.operations.infrastructure.persistence.*;
import com.readict.operations.infrastructure.persistence.entity.*;
import com.readict.operations.infrastructure.persistence.mapper.*;
import com.readict.shared.persistence.typehandler.*;
import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = TestDatabaseInitializer.class)
@Import(PersistenceTestConfiguration.class)
class FoundationIT {
    @Autowired SystemMetadataMapper mapper;
    @Autowired TypeProbeMapper probe;
    @Autowired PersistenceTestConfiguration.TransactionProbe transactionProbe;
    @MockitoSpyBean DataSource dataSource;
    @Autowired Flyway flyway;
    @Autowired SqlSessionFactory sessions;
    @Autowired ApplicationContext context;
    @Autowired ObjectMapper json;
    @MockitoSpyBean SystemMetadataRepository repository;
    @LocalServerPort int port;
    private final Set<String> keys = new HashSet<>();
    private JdbcTemplate jdbc() { return new JdbcTemplate(dataSource); }
    private String key() { String key = "it_" + UUID.randomUUID(); keys.add(key); return key; }
    private void confirmDatabase() throws SQLException {
        assertThat(probe.database()).isEqualTo("readict_test");
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getCatalog()).isEqualTo("readict_test");
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:mysql://127.0.0.1:3306/readict_test?");
            assertThat(connection.getMetaData().getUserName()).startsWith("readict_test_app@");
        }
    }
    @BeforeEach void verifyTarget() throws Exception { confirmDatabase(); }
    @AfterEach void cleanup() throws Exception {
        reset(repository, dataSource); confirmDatabase();
        for (String key : keys) { assertThat(key).startsWith("it_"); mapper.deleteById(key); }
    }
    @Test void migrationIsRepeatableAndBaselineIsUnique() {
        assertThat(mapper.selectMetadata("application").getMetadataValue()).isEqualTo("readict");
        var before = jdbc().queryForList("SELECT installed_rank,version,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc().queryForList("SELECT installed_rank,version,checksum,success FROM flyway_schema_history ORDER BY installed_rank")).isEqualTo(before);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM system_metadata WHERE metadata_key='application'",Integer.class)).isEqualTo(1);
        assertThat(jdbc().queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()",String.class)).containsExactlyInAnyOrder("system_metadata","flyway_schema_history");
    }
    @Test void baseMapperCrudAndXmlAgreeAtMillisecondPrecision() {
        var entity = new SystemMetadataEntity(); String key = key(); Instant time = Instant.parse("2026-11-19T03:04:05.123Z");
        entity.setMetadataKey(key); entity.setMetadataValue("first"); entity.setVersion(0L); entity.setUpdatedAt(time);
        assertThat(mapper.insert(entity)).isEqualTo(1);
        assertThat(mapper.selectById(key).getUpdatedAt()).isEqualTo(time);
        assertThat(mapper.selectMetadata(key).getMetadataValue()).isEqualTo("first");
        entity.setMetadataValue("second"); assertThat(mapper.updateById(entity)).isEqualTo(1);
        assertThat(mapper.selectMetadata(key).getMetadataValue()).isEqualTo("second");
        assertThat(mapper.deleteById(key)).isEqualTo(1); assertThat(mapper.selectById(key)).isNull();
    }
    @Test void applicationProxyCommitsAndReallyRollsBack() {
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(transactionProbe)).isTrue();
        String committed = key(); transactionProbe.write(committed,false);
        assertThat(mapper.selectMetadata(committed).getMetadataValue()).isEqualTo("updated");
        assertThat(mapper.selectById(committed).getVersion()).isEqualTo(1L);
        String rolledBack = key(); assertThatThrownBy(() -> transactionProbe.write(rolledBack,true)).hasMessage("controlled rollback");
        assertThat(mapper.selectMetadata(rolledBack)).isNull();
    }
    @Test void concurrentSameVersionUpdateHasOneWinnerAndRejectsReplay() throws Exception {
        String key = key(); transactionProbe.write(key,false);
        var gate = new CountDownLatch(1); var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> update = () -> { gate.await(); return mapper.updateIfVersionMatches(key,"raced",1,Instant.now()); };
            var a = executor.submit(update); var b = executor.submit(update); gate.countDown();
            assertThat(a.get(10,TimeUnit.SECONDS) + b.get(10,TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(mapper.selectById(key).getVersion()).isEqualTo(2L);
            assertThat(mapper.updateIfVersionMatches(key,"replay",1,Instant.now())).isZero();
        } finally { executor.shutdownNow(); }
    }
    @TableName(value="uuid_probe_not_created",autoResultMap=true)
    static class UuidEntity {
        @TableId(type=IdType.INPUT) @TableField(typeHandler=UuidBinaryTypeHandler.class) private UUID id;
        public UUID getId() { return id; } public void setId(UUID id) { this.id=id; }
    }
    @Test void uuidAutomaticResultMapAndRealSqlRoundTrip() {
        UUID id = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        var table = TableInfoHelper.initTableInfo(new MapperBuilderAssistant(sessions.getConfiguration(),"uuid-probe"),UuidEntity.class);
        assertThat(table.getIdType()).isEqualTo(IdType.INPUT);
        assertThat(sessions.getConfiguration().getResultMap(table.getResultMap()).getResultMappings()).anySatisfy(mapping -> {
            assertThat(mapping.getProperty()).isEqualTo("id"); assertThat(mapping.getTypeHandler()).isInstanceOf(UuidBinaryTypeHandler.class);
        });
        assertThat(probe.roundTripUuid(id)).isEqualTo(id); assertThat(probe.roundTripUuid(null)).isNull();
        assertThat(probe.uuidHex(id)).isEqualTo("00112233445566778899AABBCCDDEEFF");
    }
    @Test void realSqlUtcRoundTripUnderDifferentJvmDefaults() {
        TimeZone original = TimeZone.getDefault();
        try {
            Instant value = Instant.parse("2026-11-19T01:02:03.456Z");
            for (String zone : List.of("Asia/Shanghai","America/New_York")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertThat(probe.roundTripInstant(value)).isEqualTo(value);
            }
            assertThat(probe.roundTripInstant(null)).isNull();
            assertThat(jdbc().queryForObject("SELECT @@session.time_zone",String.class)).isIn("+00:00","UTC");
        } finally { TimeZone.setDefault(original); }
    }
    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void controlledConnectionFailureAffectsReadinessNotLiveness() throws Exception {
        doThrow(new SQLException("controlled connection fault")).when(dataSource).getConnection();
        assertThat(get("/api/v1/system/status").statusCode()).isEqualTo(503);
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(503);
        assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
    }
    @Test void httpSecurityStatusHealthAndControlledRuntimeFailure() throws Exception {
        var ok = get("/api/v1/system/status"); assertThat(ok.statusCode()).isEqualTo(200);
        var body = json.readTree(ok.body()); assertThat(body.path("status").asText()).isEqualTo("UP");
        assertThat(ok.headers().firstValue("X-Request-Id")).contains(body.path("requestId").asText());
        for (String path : List.of("/", "/login", "/actuator/health", "/actuator/env", "/api/v1/unknown")) {
            var denied = get(path); assertThat(denied.statusCode()).isIn(401,403);
            assertThat(denied.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
            assertThat(json.readTree(denied.body()).path("code").asText()).isIn("UNAUTHORIZED","FORBIDDEN");
            assertThat(denied.body()).doesNotContain("<html", "password");
        }
        assertThat(get("/actuator/health/liveness").body()).isEqualTo("{\"status\":\"UP\"}");
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
        assertThat(context.getBeansOfType(org.springframework.security.core.userdetails.UserDetailsService.class)).isEmpty();
        var client = HttpClient.newHttpClient();
        String fixedId = "00112233-4455-6677-8899-aabbccddeeff";
        var withId = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/system/status"))
                .header("X-Request-Id",fixedId).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(withId.headers().firstValue("X-Request-Id")).contains(fixedId);
        var invalidId = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/system/status"))
                .header("X-Request-Id","invalid-client-secret").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(invalidId.headers().firstValue("X-Request-Id").orElseThrow()).matches("[0-9a-f-]{36}");
        var post = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/system/status"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(403);
        assertThat(json.readTree(post.body()).path("code").asText()).isEqualTo("FORBIDDEN");
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("private SQL password"))
            .when(repository).hasApplicationBaseline();
        var unavailable = get("/api/v1/system/status"); assertThat(unavailable.statusCode()).isEqualTo(503);
        assertThat(json.readTree(unavailable.body()).path("code").asText()).isEqualTo("SYSTEM_NOT_READY");
        assertThat(unavailable.body()).doesNotContain("private", "SQL", "password");
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(503);
        assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
    }
}
