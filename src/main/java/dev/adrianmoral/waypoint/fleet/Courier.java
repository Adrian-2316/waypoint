package dev.adrianmoral.waypoint.fleet;

import dev.adrianmoral.waypoint.city.CityMap;
import dev.adrianmoral.waypoint.city.Router;
import dev.adrianmoral.waypoint.city.Vehicle;

import java.util.ArrayList;
import java.util.List;

/**
 * A courier and where they are: the last junction they passed, the junctions still ahead on the current leg,
 * and how far along the current street they are. The plan is the ordered list of stops still to make.
 */
public final class Courier {

    public enum Status { IDLE, DRIVING, AT_STOP, WAITING_FOR_PHONE }

    public final String id;
    public final String name;
    public final Vehicle vehicle;

    int from;
    final List<Integer> ahead = new ArrayList<>();
    double along;
    final List<Stop> plan = new ArrayList<>();
    double serviceLeft;
    Status status = Status.IDLE;
    boolean phone;
    int load;
    double busySeconds;

    public Courier(String id, String name, Vehicle vehicle, int startNode) {
        this.id = id;
        this.name = name;
        this.vehicle = vehicle;
        this.from = startNode;
    }

    public Status status() { return status; }
    public List<Stop> plan() { return List.copyOf(plan); }
    public int load() { return load; }
    /** True while someone drives this courier from the phone view: stops then need a tap to complete. */
    public boolean phone() { return phone; }
    public int from() { return from; }
    public List<Integer> ahead() { return List.copyOf(ahead); }
    public double busySeconds() { return busySeconds; }

    /** Stops at the head of the plan that can't be moved: the one being served right now. */
    public int lockedStops() {
        return status == Status.AT_STOP || status == Status.WAITING_FOR_PHONE ? 1 : 0;
    }

    /** The junction the courier will reach next, and the seconds until they get there. */
    public Anchor anchor(Router router, CityMap map) {
        if (status == Status.AT_STOP) return new Anchor(from, serviceLeft, true);
        if (status == Status.WAITING_FOR_PHONE) return new Anchor(from, plan.isEmpty() ? 0 : plan.getFirst().serviceSeconds(), true);
        if (ahead.isEmpty()) return new Anchor(from, 0, false);
        CityMap.Edge e = map.edge(from, ahead.getFirst());
        return new Anchor(ahead.getFirst(), (e.length() - along) / vehicle.speed(e.road()), false);
    }

    /**
     * @param serving true when the courier is at the first stop of the plan, and {@code seconds} is what's left of
     *                serving it.
     */
    public record Anchor(int node, double seconds, boolean serving) {}

    public double[] position(CityMap map) {
        CityMap.Node a = map.node(from);
        if (ahead.isEmpty()) return new double[]{a.x(), a.y()};
        CityMap.Node b = map.node(ahead.getFirst());
        CityMap.Edge e = map.edge(from, b.id());
        double f = Math.min(1, along / e.length());
        return new double[]{a.x() + (b.x() - a.x()) * f, a.y() + (b.y() - a.y()) * f};
    }
}
