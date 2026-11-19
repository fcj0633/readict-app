package com.readict.shared.config;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/** Runs after configuration loading, before any datasource or Flyway bean exists. */
public final class DatabaseEnvironmentGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override public void initialize(ConfigurableApplicationContext context) { validate(context.getEnvironment(), false); }

    public static void validate(Environment env, boolean testOnly) {
        var profiles = env.getActiveProfiles();
        require(profiles.length == 1 && Set.of("dev", "test").contains(profiles[0]));
        String profile = profiles[0];
        require(!testOnly || profile.equals("test"));
        String schema = "readict_" + profile;
        String url = env.getProperty("spring.datasource.url", "");
        validateUrl(url, schema);
        require(url.equals(env.getProperty("spring.flyway.url", url)));
        require(url.equals(env.getProperty("spring.datasource.hikari.jdbc-url", url)));
        require(env.getProperty("spring.datasource.username", "").equals(schema + "_app"));
        require(env.getProperty("spring.flyway.user", "").equals(schema + "_migrate"));
        for (String property : Arrays.asList("spring.datasource.password", "spring.flyway.password")) {
            require(!env.getProperty(property, "").isBlank());
        }
        require(env.getProperty("spring.datasource.hikari.username", schema + "_app").equals(schema + "_app"));
        require(env.getProperty("spring.datasource.hikari.password", env.getProperty("spring.datasource.password", ""))
                .equals(env.getProperty("spring.datasource.password", "")));
        require(env.getProperty("spring.flyway.enabled", Boolean.class, true));
        require(env.getProperty("spring.flyway.clean-disabled", Boolean.class, true));
        require(!env.getProperty("spring.flyway.baseline-on-migrate", Boolean.class, false));
        require(env.getProperty("spring.flyway.validate-on-migrate", Boolean.class, true));
        require(env.getProperty("spring.flyway.default-schema", schema).equals(schema));
        require(env.getProperty("spring.flyway.schemas", schema).equals(schema));
        require(env.getProperty("spring.sql.init.mode", "never").equals("never"));
        require(env.getProperty("server.address", "127.0.0.1").equals("127.0.0.1"));
        require(!env.containsProperty("management.server.port"));
    }

    private static void validateUrl(String url, String schema) {
        try {
            require(url.startsWith("jdbc:mysql://"));
            URI uri = URI.create(url.substring(5));
            require(uri.getScheme().equals("mysql") && uri.getHost().equals("127.0.0.1")
                    && uri.getPort() == 3306 && uri.getRawUserInfo() == null
                    && uri.getRawPath().equals("/" + schema) && uri.getRawFragment() == null);
            // Keep connection options explicit: no alternate endpoints, init SQL or timezone overrides.
            require("characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true".equals(uri.getRawQuery()));
        } catch (RuntimeException ignored) {
            throw new IllegalStateException("数据库配置不符合本机隔离约定；连接与迁移未开始。");
        }
    }
    private static void require(boolean valid) {
        if (!valid) throw new IllegalStateException("数据库配置不符合本机隔离约定；连接与迁移未开始。");
    }
}
