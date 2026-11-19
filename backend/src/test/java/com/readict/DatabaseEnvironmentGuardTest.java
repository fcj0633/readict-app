package com.readict;

import com.readict.shared.config.DatabaseEnvironmentGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.context.support.GenericApplicationContext;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class DatabaseEnvironmentGuardTest {
    private MockEnvironment valid() {
        return new MockEnvironment().withProperty("spring.profiles.active", "test")
            .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/readict_test?characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true")
            .withProperty("spring.datasource.username", "readict_test_app").withProperty("spring.datasource.password", "unit-secret")
            .withProperty("spring.flyway.user", "readict_test_migrate").withProperty("spring.flyway.password", "unit-secret");
    }
    @Test void acceptsOnlyExplicitIsolatedConfiguration() {
        DatabaseEnvironmentGuard.validate(valid(), true);
    }
    @Test void rejectsMisconfigurationBeforeConnectionOrMigration() {
        String[][] invalid = {
            {"spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/readict_dev?characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"},
            {"spring.profiles.active", "dev"}, {"spring.profiles.active", ""}, {"spring.profiles.active", "test,dev"},
            {"spring.datasource.username", "root"}, {"spring.datasource.password", ""},
            {"spring.flyway.url", "jdbc:mysql://127.0.0.1:3306/readict_dev"},
            {"spring.datasource.hikari.jdbc-url", "jdbc:mysql://remote:3306/readict_test"},
            {"spring.flyway.clean-disabled", "false"}, {"spring.flyway.enabled", "false"},
            {"spring.flyway.schemas", "readict_dev"}, {"spring.sql.init.mode", "always"},
            {"server.address", "0.0.0.0"}, {"spring.flyway.baseline-on-migrate", "true"}
        };
        for (String[] pair : invalid) {
            var env = valid().withProperty(pair[0], pair[1]);
            var connections = new AtomicInteger(); var migrations = new AtomicInteger();
            try (var context = new GenericApplicationContext()) {
                context.setEnvironment(env);
                context.registerBean("connectionProbe", Object.class, () -> { connections.incrementAndGet(); return new Object(); });
                context.registerBean("migrationProbe", String.class, () -> { migrations.incrementAndGet(); return "migration"; });
                assertThatThrownBy(() -> { DatabaseEnvironmentGuard.validate(context.getEnvironment(), true); context.refresh(); })
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("unit-secret");
                assertThat(connections).hasValue(0); assertThat(migrations).hasValue(0);
            }
        }
    }
}
