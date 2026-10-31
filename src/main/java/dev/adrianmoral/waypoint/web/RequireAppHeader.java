package dev.adrianmoral.waypoint.web;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Defence in depth against cross-site requests: the session cookie is already SameSite=Strict, and on top of that
 * every change must carry a header that a form on another site can't send.
 */
public class RequireAppHeader {

    static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    @ServerRequestFilter(preMatching = true)
    public Optional<Response> check(ContainerRequestContext request) {
        if (SAFE.contains(request.getMethod())) return Optional.empty();
        if ("waypoint".equals(request.getHeaderString("X-Requested-With"))) return Optional.empty();
        return Optional.of(Response.status(403).entity(Map.of("error", "Missing X-Requested-With header")).build());
    }
}
