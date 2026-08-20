package com.localdeals.init;

import com.localdeals.config.BlogLikeProperties;
import com.localdeals.service.BlogLikeCutoverService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Refuses to open production V8 writes/workers without durable cutover evidence. */
@Component
@Profile("!test")
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class BlogLikeCutoverGuard implements ApplicationRunner {

    private final BlogLikeProperties properties;
    private final BlogLikeCutoverService cutoverService;

    public BlogLikeCutoverGuard(BlogLikeProperties properties,
                                BlogLikeCutoverService cutoverService) {
        this.properties = properties;
        this.cutoverService = cutoverService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isWriteEnabled() && !properties.isWorkerEnabled()) {
            return;
        }
        if (!cutoverService.isCompleted()) {
            throw new IllegalStateException(
                    "Blog-like writes/workers require the completed V8 legacy cutover marker");
        }
    }
}
