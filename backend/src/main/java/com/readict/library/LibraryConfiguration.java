package com.readict.library;

import com.readict.library.domain.EpubLimits;
import com.readict.library.domain.ImportSettings;
import com.readict.library.infrastructure.epub.EpubPipeline;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class LibraryConfiguration {
    @Bean EpubLimits epubLimits() { return EpubLimits.defaults(); }

    @Bean EpubPipeline epubPipeline(EpubLimits limits, ObjectMapper json) { return new EpubPipeline(limits, json); }

    @Bean ImportSettings importSettings(
            @Value("${readict.library.import.concurrency:1}") int concurrency,
            @Value("${readict.library.import.queue-capacity:10}") int queueCapacity,
            @Value("${readict.library.import.per-user-limit:2}") int perUserLimit,
            @Value("${readict.library.import.lease-seconds:90}") int leaseSeconds,
            @Value("${readict.library.import.renew-seconds:20}") int renewSeconds,
            @Value("${readict.library.import.max-attempts:3}") int maxAttempts,
            @Value("${readict.library.import.timeout-seconds:60}") int timeoutSeconds) {
        return new ImportSettings(concurrency, queueCapacity, perUserLimit, leaseSeconds, renewSeconds,
                maxAttempts, timeoutSeconds);
    }

    @Bean(name = "importExecutor")
    ThreadPoolTaskExecutor importExecutor(ImportSettings settings) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(settings.concurrency());
        executor.setMaxPoolSize(settings.concurrency());
        executor.setQueueCapacity(settings.queueCapacity());
        executor.setThreadNamePrefix("book-import-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
