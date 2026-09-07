package com.notification.config;

import com.notification.domain.model.Channel;
import com.notification.domain.model.Severity;
import com.notification.domain.routing.RoutingPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.yaml.snakeyaml.Yaml;

/**
 * T063 — loads the routing policy once at startup (ADR-014, resolving U-3).
 *
 * <p>Fixed at startup rather than reloadable. FR-026 requires the policy to be changeable without
 * altering the surrounding behaviour, which a config file satisfies; it does not require a hot
 * reload, and the source states no availability target to justify one (G-11). The benefit is that
 * determinism is trivially guaranteed: no in-flight submission can straddle two versions.
 *
 * <p>Fails fast on a malformed or missing policy. Starting with a silently permissive default
 * would mean routing decisions recorded under a policy nobody authored.
 */
@Configuration
public class RoutingPolicyLoader {

    @Bean
    public RoutingPolicy routingPolicy(
            ResourceLoader loader,
            @Value("${notification.routing.policy-location:classpath:routing-policy.yaml}") String location) {

        Resource resource = loader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "Routing policy not found at " + location
                            + ". Refusing to start: routing decisions must be attributable to an"
                            + " authored policy version (FR-026).");
        }

        try (InputStream in = resource.getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            if (root == null || root.get("version") == null) {
                throw new IllegalStateException("Routing policy at " + location + " declares no version (FR-026)");
            }

            String version = String.valueOf(root.get("version"));
            Set<Channel> enabled = new LinkedHashSet<>();
            Map<Channel, Severity> floors = new EnumMap<>(Channel.class);
            Map<Channel, Severity> escalations = new EnumMap<>(Channel.class);

            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> channels =
                    (Map<String, Map<String, Object>>) root.getOrDefault("channels", Map.of());

            for (var entry : channels.entrySet()) {
                // An unknown channel name is a policy authoring error, not something to ignore:
                // silently skipping it would mean the operator believes a rule is in force when
                // it is not.
                Channel channel = Channel.valueOf(entry.getKey());
                Map<String, Object> cfg = entry.getValue() == null ? Map.of() : entry.getValue();

                if (Boolean.TRUE.equals(cfg.getOrDefault("enabled", Boolean.TRUE))) {
                    enabled.add(channel);
                }
                if (cfg.get("minimumSeverity") != null) {
                    floors.put(channel, Severity.valueOf(String.valueOf(cfg.get("minimumSeverity"))));
                }
                if (cfg.get("escalateAtSeverity") != null) {
                    escalations.put(channel, Severity.valueOf(String.valueOf(cfg.get("escalateAtSeverity"))));
                }
            }
            return new RoutingPolicy(version, enabled, floors, escalations);

        } catch (IOException e) {
            throw new IllegalStateException("Failed to read routing policy at " + location, e);
        }
    }
}
