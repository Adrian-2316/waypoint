package dev.adrianmoral.waypoint.dispatch;

import dev.adrianmoral.waypoint.city.CityMap;
import dev.adrianmoral.waypoint.city.Router;
import dev.adrianmoral.waypoint.city.Vehicle;
import dev.adrianmoral.waypoint.fleet.Courier;
import dev.adrianmoral.waypoint.fleet.Order;
import dev.adrianmoral.waypoint.fleet.Stop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides who takes an order, with cheapest insertion: try the order's pickup and drop-off at every position in
 * every courier's plan, and keep the placement that hurts least. "Hurts" means how long customers wait, promised
 * times missed (weighted heavily) and, a little, time on the road. Counting every customer's wait keeps one courier
 * from collecting a long chain of orders while others stand idle. Capacity is checked stop by stop, so a bike never
 * carries three parcels.
 */
public final class Planner {

    /** One minute late counts as much as this many minutes of a customer waiting. */
    public static final double LATE_WEIGHT = 6;
    /** One minute on the road counts as much as this much of a customer waiting. */
    public static final double ROAD_WEIGHT = 0.3;

    private final CityMap map;
    private final Map<Vehicle, Router> routers;

    public Planner(CityMap map, Map<Vehicle, Router> routers) {
        this.map = map;
        this.routers = routers;
    }

    /** When the courier would reach and leave each stop, if they followed a plan from where they are now. */
    public record Timeline(double[] arrivals, double[] departures, double finish, double waitSeconds, double lateSeconds,
                           boolean feasible) {
        public double cost(double now) {
            return feasible ? waitSeconds + lateSeconds * LATE_WEIGHT + (finish - now) * ROAD_WEIGHT : Double.POSITIVE_INFINITY;
        }
    }

    public record Insertion(Order order, Courier courier, List<Stop> plan, double extraCost, Timeline timeline) {}

    public Timeline evaluate(Courier courier, List<Stop> plan, double now) {
        Router router = routers.get(courier.vehicle);
        Courier.Anchor anchor = courier.anchor(router, map);
        double t = now + anchor.seconds();
        int node = anchor.node();
        int load = courier.load();
        double late = 0, wait = 0;
        double[] arrivals = new double[plan.size()], departures = new double[plan.size()];
        for (int i = 0; i < plan.size(); i++) {
            Stop stop = plan.get(i);
            if (i == 0 && anchor.serving()) {
                arrivals[i] = now; // already there; anchor.seconds() is what's left of serving it
            } else {
                double leg = router.seconds(node, stop.node());
                if (Double.isInfinite(leg)) return new Timeline(arrivals, departures, t, wait, late, false);
                t += leg;
                arrivals[i] = t;
                t += stop.serviceSeconds();
            }
            departures[i] = t;
            node = stop.node();
            load += stop.loadChange();
            if (load > courier.vehicle.capacity) return new Timeline(arrivals, departures, t, wait, late, false);
            if (stop.kind() == Stop.Kind.DROPOFF) {
                wait += t - now;
                late += Math.max(0, t - stop.order().promisedBy);
            }
        }
        return new Timeline(arrivals, departures, t, wait, late, true);
    }

    /** The best place for {@code order} in this courier's plan, if it fits at all. */
    public Optional<Insertion> bestInsertion(Order order, Courier courier, double now) {
        List<Stop> current = courier.plan();
        double before = evaluate(courier, current, now).cost(now);
        Insertion best = null;
        int first = courier.lockedStops();
        for (int p = first; p <= current.size(); p++) {
            for (int d = p; d <= current.size(); d++) {
                List<Stop> plan = new ArrayList<>(current);
                plan.add(d, new Stop(order, Stop.Kind.DROPOFF));
                plan.add(p, new Stop(order, Stop.Kind.PICKUP));
                Timeline timeline = evaluate(courier, plan, now);
                double extra = timeline.cost(now) - before;
                if (timeline.feasible() && (best == null || extra < best.extraCost())) {
                    best = new Insertion(order, courier, plan, extra, timeline);
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** The best courier and placement for {@code order} across the whole fleet. */
    public Optional<Insertion> bestInsertion(Order order, List<Courier> couriers, double now) {
        return couriers.stream()
            .map(c -> bestInsertion(order, c, now))
            .flatMap(Optional::stream)
            .min((a, b) -> Double.compare(a.extraCost(), b.extraCost()));
    }
}
