package com.seuprojeto.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * The executor that writes retrieval analytics off the request thread.
 *
 * <p>Declared explicitly, and referenced by name from {@code @Async}, rather than relying on
 * whichever {@code Executor} bean happens to be unique in the context — this application also has
 * a task scheduler, and {@code @Async} resolving to the wrong pool would put analytics inserts on
 * the thread that runs the session and conversation purges.
 *
 * <p>The queue is bounded and a full queue <em>drops</em> the work with a warning. Analytics is
 * allowed to be lossy (see {@code RetrievalLogger}); what is not allowed is the default rejection
 * behaviour, which throws {@code RejectedExecutionException} <em>in the calling thread</em> and
 * would turn a saturated analytics pool into a failed user request.
 */
@Configuration
@EnableAsync
public class AnalyticsConfig {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsConfig.class);

    public static final String EXECUTOR = "analyticsExecutor";

    @Bean(EXECUTOR)
    public Executor analyticsExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("analytics-");
        executor.setRejectedExecutionHandler((task, pool) ->
                log.warn("Analytics queue is full; dropped one retrieval-log batch. "
                        + "Dashboard counts will undercount until load drops."));
        executor.initialize();
        return executor;
    }
}
