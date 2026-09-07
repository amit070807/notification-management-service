package com.notification.config;

import com.notification.domain.routing.RoutingPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the routing policy.
 *
 * <p>US1 uses a permissive policy so acceptance can record a decision, which Principle II requires
 * inside the transaction. US3 (T061-T064) replaces this with the versioned YAML resource loaded at
 * startup per ADR-014. The version string names it plainly so a decision recorded now is not
 * mistaken later for one made under a real policy.
 */
@Configuration
public class RoutingPolicyConfig {

    @Bean
    public RoutingPolicy routingPolicy() {
        return RoutingPolicy.allowAll("identity-us1");
    }
}
