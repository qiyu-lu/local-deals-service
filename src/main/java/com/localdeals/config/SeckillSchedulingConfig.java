package com.localdeals.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Enables bounded background workers on an explicit scheduler shared by reliability jobs. */
@Configuration
@EnableScheduling
public class SeckillSchedulingConfig {

    /**
     * Spring WebSocket exposes a nullable SockJS scheduler bean even when SockJS is unused.
     * Naming the application scheduler explicitly prevents scheduled workers from resolving that
     * null placeholder as the only {@link TaskScheduler} candidate during context startup.
    */
    @Bean(name = "taskScheduler")
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("local-deals-scheduled-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
