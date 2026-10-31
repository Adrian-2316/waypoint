package dev.adrianmoral.waypoint.web;

import dev.adrianmoral.waypoint.fleet.Simulation;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.Map;

public class Errors {

    /** A command the simulation turned down, such as giving a full bike another parcel. */
    @ServerExceptionMapper
    public Response rejected(Simulation.Rejected e) {
        return Response.status(409).entity(Map.of("error", e.getMessage())).build();
    }
}
