package dev.adrianmoral.waypoint.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.adrianmoral.waypoint.city.CityGenerator;
import dev.adrianmoral.waypoint.fleet.Simulation;
import dev.adrianmoral.waypoint.store.DeliveryLog;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.websockets.next.OpenConnections;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Runs the simulation on one thread. Every tick it advances the clock, then pushes a snapshot to every open
 * board and phone. Requests that change things are queued onto the same thread, so the simulation never needs a lock.
 */
@ApplicationScoped
public class LiveService {

    private static final Logger LOG = Logger.getLogger(LiveService.class);
    static final long TICK_MS = 500;

    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "simulation"));
    private final ObjectMapper json;
    private final OpenConnections connections;
    private final DeliveryLog log;
    private final long seed;
    private final double startClock;
    private final double warmUpMinutes;
    private final boolean autoTick;

    private Simulation sim;
    private volatile String city;
    private volatile String latest = "{}";
    private double speed;
    private boolean paused;

    public LiveService(ObjectMapper json, OpenConnections connections, DeliveryLog log,
                       @ConfigProperty(name = "waypoint.seed", defaultValue = "42") long seed,
                       @ConfigProperty(name = "waypoint.start-clock", defaultValue = "11:30") String startClock,
                       @ConfigProperty(name = "waypoint.warm-up-minutes", defaultValue = "25") double warmUpMinutes,
                       @ConfigProperty(name = "waypoint.speed", defaultValue = "10") double speed,
                       @ConfigProperty(name = "waypoint.auto-tick", defaultValue = "true") boolean autoTick) {
        this.json = json;
        this.connections = connections;
        this.log = log;
        this.seed = seed;
        String[] hm = startClock.split(":");
        this.startClock = Integer.parseInt(hm[0]) * 3600 + Integer.parseInt(hm[1]) * 60;
        this.warmUpMinutes = warmUpMinutes;
        this.speed = speed;
        this.autoTick = autoTick;
    }

    void start(@Observes StartupEvent ignored) {
        call(s -> {
            sim = new Simulation(CityGenerator.generate(7), seed, startClock - warmUpMinutes * 60);
            sim.setListener((order, now) -> log.save(order));
            // Start mid-afternoon, so the board is busy from the first second.
            for (double t = 0; t < warmUpMinutes * 60; t += 5) sim.step(5);
            city = write(Views.city(sim));
            publish();
            return null;
        });
        if (autoTick) loop.scheduleAtFixedRate(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        LOG.infof("Simulation running at %sx, %d couriers", speed, sim.couriers().size());
    }

    void stop(@Observes ShutdownEvent ignored) {
        loop.shutdownNow();
    }

    /** Advances the simulation by one tick. Public so tests can drive the clock themselves. */
    public void tick() {
        try {
            if (!paused) sim.step(speed * TICK_MS / 1000.0);
            publish();
        } catch (RuntimeException e) {
            LOG.error("Simulation tick failed", e);
        }
    }

    /** Runs {@code action} on the simulation thread and waits for its result. */
    public <T> T call(Function<Simulation, T> action) {
        try {
            return loop.submit(() -> action.apply(sim)).get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("The simulation is busy, try again", e);
        }
    }

    /** Changes to speed and pause go through the simulation thread too. */
    public void configure(Double newSpeed, Boolean pause) {
        call(s -> {
            if (newSpeed != null) speed = newSpeed;
            if (pause != null) paused = pause;
            publish();
            return null;
        });
    }

    public String city() {
        return city;
    }

    public String latest() {
        return latest;
    }

    /** Must run on the simulation thread. */
    void publish() {
        latest = write(Views.snapshot(sim, speed, paused, startClock - warmUpMinutes * 60));
        String message = latest;
        connections.forEach(c -> c.sendText(message).subscribe().with(ok -> {}, err -> LOG.debug("Dropped a frame", err)));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
