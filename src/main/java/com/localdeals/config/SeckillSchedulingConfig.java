package com.localdeals.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables the bounded, configuration-gated seckill reconciliation worker. */
@Configuration
@EnableScheduling
public class SeckillSchedulingConfig {
}
