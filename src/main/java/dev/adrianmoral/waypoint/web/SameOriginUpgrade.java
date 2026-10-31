package dev.adrianmoral.waypoint.web;

import io.quarkus.websockets.next.HttpUpgradeCheck;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;

/**
 * Browsers attach cookies to WebSocket handshakes from any site, and CORS doesn't apply to them. Only pages
 * served by Waypoint itself may open the live feed.
 */
@ApplicationScoped
public class SameOriginUpgrade implements HttpUpgradeCheck {

    @Override
    public Uni<CheckResult> perform(HttpUpgradeContext context) {
        String origin = context.httpRequest().getHeader("Origin");
        String host = context.httpRequest().getHeader("Host");
        if (origin == null || host == null) return CheckResult.rejectUpgrade(403);
        try {
            URI uri = URI.create(origin);
            String originHost = uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
            return host.equalsIgnoreCase(originHost) ? CheckResult.permitUpgrade() : CheckResult.rejectUpgrade(403);
        } catch (IllegalArgumentException e) {
            return CheckResult.rejectUpgrade(403);
        }
    }
}
