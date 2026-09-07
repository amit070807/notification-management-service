package com.notification.api;

import com.notification.api.dto.NotificationStatusResponse;
import com.notification.application.StatusQueryService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T055 — status retrieval (source 4.2).
 *
 * <p>Keyed by the server-issued identity, not the client identifier: the latter is not unique
 * after spec D4, so it cannot address a single notification. A client identifier passed here fails
 * UUID parsing and is rejected, which is the desired outcome — it is not a retrieval key.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class StatusController {

    private final StatusQueryService statuses;

    public StatusController(StatusQueryService statuses) {
        this.statuses = statuses;
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<NotificationStatusResponse> status(@PathVariable UUID id) {
        return ResponseEntity.ok(statuses.statusOf(id));
    }
}
