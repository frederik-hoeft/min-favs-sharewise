package de.hsh.favs.sharewise.shared.infra.health;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the versioned system health and readiness endpoint ({@code GET /api/system/v1/health}).
 */
@RestController
public final class SystemHealthController {

    private static final Logger LOG = LoggerFactory.getLogger(SystemHealthController.class);

    private final List<ReadinessIndicator> indicators;

    public SystemHealthController(List<ReadinessIndicator> indicators) {
        this.indicators = List.copyOf(Objects.requireNonNull(indicators, "indicators must not be null"));
    }

    @GetMapping("/api/system/v1/health")
    public ResponseEntity<HealthResponse> health() {
        boolean ready = evaluateReadiness();
        if (ready) {
            return ResponseEntity.ok(new HealthResponse("UP"));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new HealthResponse("DOWN"));
    }

    private boolean evaluateReadiness() {
        for (ReadinessIndicator indicator : indicators) {
            try {
                if (!indicator.isReady()) {
                    return false;
                }
            } catch (RuntimeException exception) {
                LOG.warn("Readiness indicator threw an exception", exception);
                return false;
            }
        }
        return true;
    }

    public record HealthResponse(String status) {
    }
}
