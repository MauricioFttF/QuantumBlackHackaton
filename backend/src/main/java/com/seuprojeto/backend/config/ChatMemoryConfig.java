package com.seuprojeto.backend.config;

import com.seuprojeto.backend.service.ConversationMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Wiring for conversation memory: the task that deletes expired turns. The clock they share
 * lives in {@link TimeConfig} — see the note there for why it cannot live here.
 *
 * <p>The purge is registered programmatically rather than with {@code @Scheduled(fixedRateString
 * = "...")} so the interval comes from the typed {@link ChatMemoryProperties#cleanupInterval()}
 * instead of a string parsed at annotation-processing time — a typo in the property then fails at
 * startup with a readable message rather than being silently reinterpreted.
 */
@Configuration
@EnableScheduling
public class ChatMemoryConfig implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryConfig.class);

    private final ChatMemoryProperties properties;
    private final ConversationMemory conversationMemory;

    public ChatMemoryConfig(ChatMemoryProperties properties, ConversationMemory conversationMemory) {
        this.properties = properties;
        this.conversationMemory = conversationMemory;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!properties.enabled()) {
            log.info("Chat memory disabled; not scheduling the conversation purge");
            return;
        }
        log.info("Purging conversation turns older than {} every {}",
                properties.ttl(), properties.cleanupInterval());
        registrar.addFixedRateTask(conversationMemory::purgeExpired, properties.cleanupInterval());
    }
}
