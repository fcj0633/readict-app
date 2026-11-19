package com.readict.support;

import com.readict.shared.config.DatabaseEnvironmentGuard;
import org.springframework.context.*;

public class TestDatabaseInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override public void initialize(ConfigurableApplicationContext context) {
        DatabaseEnvironmentGuard.validate(context.getEnvironment(), true);
    }
}
