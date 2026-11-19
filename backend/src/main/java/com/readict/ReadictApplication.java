package com.readict;

import com.readict.shared.config.DatabaseEnvironmentGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(excludeName = "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration")
public class ReadictApplication {
    public static void main(String[] args) {
        System.setProperty("user.timezone", "UTC");
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        SpringApplication app = new SpringApplication(ReadictApplication.class);
        app.addInitializers(new DatabaseEnvironmentGuard());
        app.run(args);
    }
}
