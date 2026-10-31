package dev.adrianmoral.waypoint.fleet;

import dev.adrianmoral.waypoint.city.CityGenerator;
import dev.adrianmoral.waypoint.city.CityMap;
import dev.adrianmoral.waypoint.city.Router;
import dev.adrianmoral.waypoint.city.Vehicle;
import dev.adrianmoral.waypoint.dispatch.Planner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A working afternoon in Valdera: orders come in from the shops, couriers ride their plans, and the dispatcher
 * places every new order. Not thread-safe on purpose: one thread owns it and everything else sends it commands.
 * Times are seconds since midnight.
 */
public final class Simulation {

    public enum Demand {
        CALM(25), STEADY(45), RUSH(80);

        public final int perHour;

        Demand(int perHour) {
            this.perHour = perHour;
        }
    }

    public record Event(double at, String kind, String text) {}

    /** Told about every order change, so it can be stored. */
    public interface Listener {
        void orderChanged(Order order, double now);
    }

    public static class Rejected extends RuntimeException {
        public Rejected(String message) {
            super(message);
        }
    }

    static final double SUBSTEP = 2.0;
    static final double IMPROVE_EVERY = 60, IMPROVE_MIN_SAVING = 120;
    static final double KEEP_DELIVERED = 20 * 60;

    static final String[][] COURIERS = {
        {"lucia", "Lucía", "BIKE"}, {"marco", "Marco", "VAN"}, {"aitana", "Aitana", "BIKE"}, {"hugo", "Hugo", "BIKE"},
        {"nerea", "Nerea", "VAN"}, {"iker", "Iker", "BIKE"}, {"carmen", "Carmen", "BIKE"}, {"dani", "Dani", "VAN"},
        {"sofia", "Sofía", "BIKE"}, {"pablo", "Pablo", "VAN"},
    };
    static final String[] CUSTOMERS = {"Elena R.", "Javier M.", "Ana P.", "Luis G.", "Marta S.", "Raúl T.", "Irene V.",
        "Óscar L.", "Paula F.", "Diego H.", "Clara N.", "Álvaro B.", "Lola C.", "Sergio D.", "Inés A.", "Tomás E."};

    public final CityMap map;
    public final int depot;
    private final Map<Vehicle, Router> routers = new EnumMap<>(Vehicle.class);
    private final Planner planner;
    private final Random rnd;
    private final List<Courier> couriers = new ArrayList<>();
    private final Map<String, Order> orders = new LinkedHashMap<>();
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final List<Integer> doorsteps;

    private double now;
    private double lastImprove;
    private boolean autoDispatch = true;
    private Demand demand = Demand.STEADY;
    private int nextOrder = 1001;
    private int delivered, onTime;
    private double deliveryMinutes;
    private Listener listener = (o, t) -> {};

    public Simulation(CityMap map, long seed, double startClock) {
        this.map = map;
        this.rnd = new Random(seed);
        this.now = startClock;
        this.lastImprove = startClock;
        for (Vehicle v : Vehicle.values()) routers.put(v, new Router(map, v));
        this.planner = new Planner(map, routers);
        this.depot = CityGenerator.depot(map);
        for (String[] c : COURIERS) couriers.add(new Courier(c[0], c[1], Vehicle.valueOf(c[2]), depot));
        // Doors are on streets vans can reach, so every order can go to anyone.
        Router van = routers.get(Vehicle.VAN);
        this.doorsteps = map.nodes.stream().map(CityMap.Node::id)
            .filter(n -> !Double.isInfinite(van.seconds(depot, n)) && !map.adjacency.get(n).isEmpty()).toList();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    // ---- reading ----

    public double now() { return now; }
    public boolean autoDispatch() { return autoDispatch; }
    public Demand demand() { return demand; }
    public List<Courier> couriers() { return List.copyOf(couriers); }
    public List<Order> orders() { return List.copyOf(orders.values()); }
    public List<Event> events() { return List.copyOf(events); }
    public int delivered() { return delivered; }
    public int deliveredOnTime() { return onTime; }
    public double averageDeliveryMinutes() { return delivered == 0 ? 0 : deliveryMinutes / delivered; }
    public Router router(Vehicle v) { return routers.get(v); }
    public Planner planner() { return planner; }

    public Order order(String id) {
        Order o = orders.get(id);
        if (o == null) throw new Rejected("No order " + id);
        return o;
    }

    public Courier courier(String id) {
        return couriers.stream().filter(c -> c.id.equals(id)).findFirst().orElseThrow(() -> new Rejected("No courier " + id));
    }

    /** The streets a courier will ride: from where they are, through every stop in their plan. */
    public List<double[]> route(Courier c) {
        List<double[]> line = new ArrayList<>();
        line.add(c.position(map));
        int node = c.from;
        if (!c.ahead.isEmpty()) {
            for (int n : c.ahead) line.add(xy(n));
            node = c.ahead.getLast();
        }
        Router router = routers.get(c.vehicle);
        for (int i = c.ahead.isEmpty() ? 0 : 1; i < c.plan.size(); i++) {
            int target = c.plan.get(i).node();
            if (target == node) continue;
            List<Integer> path = router.path(node, target);
            for (int n : path.subList(1, path.size())) line.add(xy(n));
            node = target;
        }
        return line;
    }

    // ---- commands ----

    public void setAutoDispatch(boolean on) {
        autoDispatch = on;
        log("info", on ? "Automatic dispatch is on" : "Automatic dispatch is off: new orders wait for you");
    }

    public void setDemand(Demand demand) {
        this.demand = demand;
    }

    public Order createOrder(String merchantId, String customer, double x, double y, int size) {
        CityMap.Place merchant = map.merchants.stream().filter(m -> m.id().equals(merchantId)).findFirst()
            .orElseThrow(() -> new Rejected("No shop " + merchantId));
        int door = nearestDoorstep(x, y);
        if (door == merchant.node()) throw new Rejected("Pick a drop-off away from the shop");
        Order o = newOrder(merchant, customer, door, size);
        log("new", o.id + " from " + merchant.name() + " added by the dispatcher");
        return o;
    }

    /** A dispatcher's choice: this courier takes this order. Automatic re-planning won't move it again. */
    public Planner.Insertion assign(String orderId, String courierId) {
        Order o = order(orderId);
        Courier target = courier(courierId);
        if (o.status == Order.Status.PICKED_UP || o.status == Order.Status.DELIVERED) {
            throw new Rejected(o.id + " is already with " + courier(o.courierId).name);
        }
        if (target.id.equals(o.courierId)) throw new Rejected(o.id + " is already with " + target.name);
        if (o.courierId != null && isServing(courier(o.courierId), o)) {
            throw new Rejected(courier(o.courierId).name + " is collecting " + o.id + " right now");
        }
        var fit = planner.bestInsertion(o, target, now)
            .orElseThrow(() -> new Rejected(target.name + "'s " + target.vehicle.name().toLowerCase() + " has no room for " + o.id));
        if (o.courierId != null) removeFromPlan(courier(o.courierId), o);
        o.unassign();
        apply(fit, "dispatcher");
        o.pinned = true;
        return fit;
    }

    /** Hands a courier to (or back from) the phone view. Stops then wait for a tap instead of completing on their own. */
    public void setPhone(String courierId, boolean on) {
        Courier c = courier(courierId);
        c.phone = on;
        if (!on && c.status == Courier.Status.WAITING_FOR_PHONE) startService(c);
    }

    /** The courier tapped "Picked up" or "Delivered" on their phone. */
    public Stop completeStop(String courierId) {
        Courier c = courier(courierId);
        if (c.status != Courier.Status.WAITING_FOR_PHONE && c.status != Courier.Status.AT_STOP) {
            throw new Rejected(c.name + " isn't at a stop yet");
        }
        return finishStop(c);
    }

    // ---- time ----

    public void step(double seconds) {
        double left = seconds;
        while (left > 1e-9) {
            double dt = Math.min(SUBSTEP, left);
            left -= dt;
            now += dt;
            spawn(dt);
            for (Courier c : couriers) move(c, dt);
            if (autoDispatch) {
                assignWaiting();
                if (now - lastImprove >= IMPROVE_EVERY) {
                    improve();
                    lastImprove = now;
                }
            }
        }
        updateEtas();
        orders.values().removeIf(o -> o.deliveredAt != null && now - o.deliveredAt > KEEP_DELIVERED);
    }

    private void spawn(double dt) {
        if (rnd.nextDouble() < demand.perHour / 3600.0 * dt) {
            CityMap.Place merchant = map.merchants.get(rnd.nextInt(map.merchants.size()));
            int door;
            double distance;
            do {
                door = doorsteps.get(rnd.nextInt(doorsteps.size()));
                CityMap.Node a = map.node(merchant.node()), b = map.node(door);
                distance = Math.hypot(a.x() - b.x(), a.y() - b.y());
            } while (distance < 500 || distance > 2600);
            Order o = newOrder(merchant, CUSTOMERS[rnd.nextInt(CUSTOMERS.length)], door, rnd.nextDouble() < 0.2 ? 2 : 1);
            log("new", o.id + " from " + merchant.name() + " for " + o.customer);
        }
    }

    private Order newOrder(CityMap.Place merchant, String customer, int door, int size) {
        if (size < 1 || size > 2) throw new Rejected("Size is 1 or 2 units");
        double direct = routers.get(Vehicle.VAN).seconds(merchant.node(), door);
        // Promise at least 30 minutes, rounded up to the next 5, with room for the courier to get there.
        double promise = Math.ceil((now + Math.max(30 * 60, direct + 20 * 60)) / 300) * 300;
        String address = map.node(door).street() + ", " + (1 + rnd.nextInt(90));
        Order o = new Order("W-" + nextOrder++, merchant, customer, address, door, size, now, promise);
        orders.put(o.id, o);
        listener.orderChanged(o, now);
        return o;
    }

    private void move(Courier c, double dt) {
        switch (c.status) {
            case AT_STOP -> {
                c.busySeconds += dt;
                c.serviceLeft -= dt;
                if (c.serviceLeft <= 0) finishStop(c);
            }
            case WAITING_FOR_PHONE -> c.busySeconds += dt;
            case IDLE, DRIVING -> {
                if (!c.plan.isEmpty()) c.busySeconds += dt;
                if (c.ahead.isEmpty() && !c.plan.isEmpty() && c.from == c.plan.getFirst().node()) {
                    arrive(c);
                    return;
                }
                double left = dt;
                while (left > 0 && !c.ahead.isEmpty()) {
                    CityMap.Edge e = map.edge(c.from, c.ahead.getFirst());
                    double speed = c.vehicle.speed(e.road());
                    double remaining = e.length() - c.along;
                    if (speed * left < remaining) {
                        c.along += speed * left;
                        left = 0;
                    } else {
                        left -= remaining / speed;
                        c.from = c.ahead.removeFirst();
                        c.along = 0;
                        if (c.ahead.isEmpty()) {
                            if (!c.plan.isEmpty() && c.from == c.plan.getFirst().node()) arrive(c);
                            else reroute(c);
                            if (c.status != Courier.Status.DRIVING) break;
                        }
                    }
                }
            }
        }
    }

    private void arrive(Courier c) {
        Stop stop = c.plan.getFirst();
        if (c.phone) {
            c.status = Courier.Status.WAITING_FOR_PHONE;
            log("phone", c.name + " is at " + where(stop) + " and needs to tap " + (stop.kind() == Stop.Kind.PICKUP ? "Picked up" : "Delivered"));
        } else {
            startService(c);
        }
    }

    private void startService(Courier c) {
        c.status = Courier.Status.AT_STOP;
        c.serviceLeft = c.plan.getFirst().serviceSeconds();
    }

    private Stop finishStop(Courier c) {
        Stop stop = c.plan.removeFirst();
        Order o = stop.order();
        if (stop.kind() == Stop.Kind.PICKUP) {
            o.status = Order.Status.PICKED_UP;
            o.pickedUpAt = now;
            c.load += o.size;
            log("pickup", c.name + " picked up " + o.id + " at " + o.merchant.name());
        } else {
            o.status = Order.Status.DELIVERED;
            o.deliveredAt = now;
            o.eta = now;
            c.load -= o.size;
            delivered++;
            deliveryMinutes += (now - o.createdAt) / 60;
            if (now <= o.promisedBy) {
                onTime++;
                log("delivered", c.name + " delivered " + o.id + " to " + o.customer);
            } else {
                log("late", c.name + " delivered " + o.id + " " + Math.round((now - o.promisedBy) / 60) + " min late");
            }
        }
        listener.orderChanged(o, now);
        c.status = c.plan.isEmpty() ? Courier.Status.IDLE : Courier.Status.DRIVING;
        reroute(c);
        return stop;
    }

    // ---- dispatch ----

    private void assignWaiting() {
        orders.values().stream()
            .filter(o -> o.status == Order.Status.WAITING)
            .sorted(Comparator.comparingDouble(o -> o.promisedBy))
            .toList()
            .forEach(o -> planner.bestInsertion(o, couriers, now).ifPresent(fit -> apply(fit, "auto")));
    }

    /** Moves an order to another courier when that saves at least two minutes overall. */
    private void improve() {
        for (Order o : List.copyOf(orders.values())) {
            if (o.status != Order.Status.ASSIGNED || o.pinned) continue;
            Courier owner = courier(o.courierId);
            if (isServing(owner, o)) continue;
            List<Stop> without = new ArrayList<>(owner.plan);
            without.removeIf(s -> s.order() == o);
            double saving = planner.evaluate(owner, owner.plan, now).cost(now) - planner.evaluate(owner, without, now).cost(now);
            var better = couriers.stream().filter(c -> c != owner)
                .map(c -> planner.bestInsertion(o, c, now)).flatMap(java.util.Optional::stream)
                .min(Comparator.comparingDouble(Planner.Insertion::extraCost));
            if (better.isPresent() && saving - better.get().extraCost() >= IMPROVE_MIN_SAVING) {
                removeFromPlan(owner, o);
                o.unassign();
                apply(better.get(), "moved");
                log("moved", o.id + " moved from " + owner.name + " to " + better.get().courier().name + ", saves "
                    + Math.round((saving - better.get().extraCost()) / 60) + " min");
            }
        }
    }

    private void apply(Planner.Insertion fit, String how) {
        Courier c = fit.courier();
        Order o = fit.order();
        c.plan.clear();
        c.plan.addAll(fit.plan());
        o.assign(c.id, now);
        if (c.status == Courier.Status.IDLE) c.status = Courier.Status.DRIVING;
        reroute(c);
        if (!how.equals("moved")) {
            log("assigned", o.id + " → " + c.name + (how.equals("dispatcher") ? " (by hand)" : "") + ", due " + clock(o.promisedBy));
        }
        listener.orderChanged(o, now);
    }

    private void removeFromPlan(Courier c, Order o) {
        c.plan.removeIf(s -> s.order() == o);
        if (c.plan.isEmpty() && c.status == Courier.Status.DRIVING) c.status = Courier.Status.IDLE;
        reroute(c);
    }

    private boolean isServing(Courier c, Order o) {
        return c.lockedStops() > 0 && c.plan.getFirst().order() == o;
    }

    /** Points the courier at the first stop of their plan. Mid-street, they turn around if that's quicker. */
    private void reroute(Courier c) {
        if (c.status == Courier.Status.AT_STOP || c.status == Courier.Status.WAITING_FOR_PHONE) return;
        Integer onStreet = c.ahead.isEmpty() ? null : c.ahead.getFirst();
        if (onStreet != null && !c.plan.isEmpty()) {
            Router router = routers.get(c.vehicle);
            CityMap.Edge e = map.edge(c.from, onStreet);
            double speed = c.vehicle.speed(e.road());
            int target = c.plan.getFirst().node();
            double forward = (e.length() - c.along) / speed + router.seconds(onStreet, target);
            double back = c.along / speed + router.seconds(c.from, target);
            if (back < forward) {
                int behind = c.from;
                c.from = onStreet;
                c.along = e.length() - c.along;
                onStreet = behind;
            }
        }
        c.ahead.clear();
        if (onStreet != null) c.ahead.add(onStreet);
        if (c.plan.isEmpty()) {
            if (onStreet == null) c.status = Courier.Status.IDLE;
            return;
        }
        int start = onStreet != null ? onStreet : c.from;
        List<Integer> path = routers.get(c.vehicle).path(start, c.plan.getFirst().node());
        c.ahead.addAll(path.subList(1, path.size()));
        c.status = Courier.Status.DRIVING;
    }

    private void updateEtas() {
        for (Courier c : couriers) {
            Planner.Timeline t = planner.evaluate(c, c.plan, now);
            for (int i = 0; i < c.plan.size(); i++) {
                Stop s = c.plan.get(i);
                if (s.kind() == Stop.Kind.DROPOFF) {
                    s.order().eta = t.departures()[i];
                }
            }
        }
    }

    // ---- helpers ----

    private int nearestDoorstep(double x, double y) {
        return doorsteps.stream().min(Comparator.comparingDouble(n -> Math.hypot(map.node(n).x() - x, map.node(n).y() - y)))
            .orElseThrow();
    }

    private String where(Stop s) {
        return s.kind() == Stop.Kind.PICKUP ? s.order().merchant.name() : s.order().address;
    }

    private double[] xy(int node) {
        return new double[]{map.node(node).x(), map.node(node).y()};
    }

    private void log(String kind, String text) {
        events.addFirst(new Event(now, kind, text));
        while (events.size() > 60) events.removeLast();
    }

    public static String clock(double seconds) {
        int m = (int) Math.round(seconds / 60);
        return String.format("%02d:%02d", (m / 60) % 24, m % 60);
    }
}
