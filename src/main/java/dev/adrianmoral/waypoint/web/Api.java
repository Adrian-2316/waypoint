package dev.adrianmoral.waypoint.web;

import dev.adrianmoral.waypoint.fleet.Simulation;
import dev.adrianmoral.waypoint.store.DeliveryLog;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class Api {

    static final String ID = "[a-z0-9-]{1,32}";
    static final String ORDER_ID = "W-[0-9]{1,8}";

    private final LiveService live;
    private final DeliveryLog log;
    private final SecurityIdentity identity;

    public Api(LiveService live, DeliveryLog log, SecurityIdentity identity) {
        this.live = live;
        this.log = log;
        this.identity = identity;
    }

    public record Me(String user, String role) {}

    @GET
    @Path("/me")
    public Me me() {
        return new Me(identity.getPrincipal().getName(), identity.hasRole("dispatcher") ? "dispatcher" : "courier");
    }

    @POST
    @Path("/logout")
    public Response logout() {
        // The session lives in an encrypted cookie, so signing out means expiring it.
        return Response.noContent()
            .cookie(new NewCookie.Builder("quarkus-credential").value("").path("/").maxAge(0).httpOnly(true)
                .sameSite(NewCookie.SameSite.STRICT).build())
            .build();
    }

    @GET
    @Path("/city")
    public String city() {
        return live.city();
    }

    @GET
    @Path("/state")
    public String state() {
        return live.latest();
    }

    @GET
    @Path("/report")
    @RolesAllowed("dispatcher")
    public List<DeliveryLog.CourierReport> report() {
        return log.report();
    }

    public record NewOrder(@NotBlank @Pattern(regexp = ID) String merchantId,
                           @NotBlank @Size(max = 60) String customer,
                           @NotNull @DecimalMin("0") @DecimalMax("4000") Double x,
                           @NotNull @DecimalMin("0") @DecimalMax("2750") Double y,
                           @Min(1) @Max(2) int size) {}

    @POST
    @Path("/orders")
    @RolesAllowed("dispatcher")
    public Response create(@Valid @NotNull NewOrder body) {
        String id = live.call(s -> s.createOrder(body.merchantId(), body.customer().strip(), body.x(), body.y(), body.size()).id);
        return Response.status(201).entity(Map.of("id", id)).build();
    }

    public record Assign(@NotBlank @Pattern(regexp = ID) String courierId) {}

    @POST
    @Path("/orders/{id}/assign")
    @RolesAllowed("dispatcher")
    public Map<String, Object> assign(@PathParam("id") @Pattern(regexp = ORDER_ID) String id, @Valid @NotNull Assign body) {
        return live.call(s -> {
            var fit = s.assign(id, body.courierId());
            return Map.of("courierId", fit.courier().id, "extraMinutes", Math.round(fit.extraCost() / 60));
        });
    }

    public record Settings(Boolean autoDispatch, Simulation.Demand demand,
                           @DecimalMin("0.5") @DecimalMax("30") Double speed, Boolean paused) {}

    @PUT
    @Path("/settings")
    @RolesAllowed("dispatcher")
    public Response settings(@Valid @NotNull Settings body) {
        live.call(s -> {
            if (body.autoDispatch() != null && body.autoDispatch() != s.autoDispatch()) s.setAutoDispatch(body.autoDispatch());
            if (body.demand() != null) s.setDemand(body.demand());
            return null;
        });
        live.configure(body.speed(), body.paused());
        return Response.noContent().build();
    }

    public record Phone(boolean on) {}

    @PUT
    @Path("/couriers/{id}/phone")
    public Response phone(@PathParam("id") @Pattern(regexp = ID) String id, @Valid @NotNull Phone body) {
        live.call(s -> {
            s.setPhone(id, body.on());
            return null;
        });
        return Response.noContent().build();
    }

    @POST
    @Path("/couriers/{id}/complete")
    public Map<String, String> complete(@PathParam("id") @Pattern(regexp = ID) String id) {
        return live.call(s -> {
            var stop = s.completeStop(id);
            return Map.of("orderId", stop.order().id, "kind", stop.kind().name().toLowerCase());
        });
    }
}
