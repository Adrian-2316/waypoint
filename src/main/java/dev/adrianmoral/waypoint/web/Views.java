package dev.adrianmoral.waypoint.web;

import dev.adrianmoral.waypoint.city.CityMap;
import dev.adrianmoral.waypoint.fleet.Courier;
import dev.adrianmoral.waypoint.fleet.Order;
import dev.adrianmoral.waypoint.fleet.Simulation;
import dev.adrianmoral.waypoint.fleet.Stop;

import java.util.Comparator;
import java.util.List;

/** What the board and the courier phone receive. Times are simulation seconds since midnight. */
public final class Views {

    private Views() {}

    public record City(double width, double height, List<double[]> nodes, List<Road> roads, List<Place> merchants,
                       List<CityMap.Area> areas, double[] depot) {}

    public record Road(int a, int b, String kind) {}

    public record Place(String id, String name, String kind, double x, double y) {}

    public record Snapshot(double clock, double speed, boolean paused, boolean autoDispatch, String demand,
                           List<CourierView> couriers, List<OrderView> orders, Kpis kpis, List<Simulation.Event> events) {}

    public record CourierView(String id, String name, String vehicle, int capacity, int load, String status, boolean phone,
                              double x, double y, List<double[]> route, List<StopView> stops, double busyShare) {}

    /** {@code arrival}: when the courier is expected at the stop. */
    public record StopView(String orderId, String kind, String label, double x, double y, double arrival) {}

    public record OrderView(String id, String merchant, String merchantId, String customer, String address, int size,
                            String status, String courierId, double createdAt, double promisedBy, Double eta,
                            Double pickedUpAt, Double deliveredAt, boolean late, boolean pinned,
                            double[] pickup, double[] dropoff) {}

    public record Kpis(int active, int waiting, int delivered, int onTimePercent, double averageMinutes, int busyCouriers) {}

    static City city(Simulation sim) {
        CityMap m = sim.map;
        return new City(m.width, m.height,
            m.nodes.stream().map(n -> new double[]{n.x(), n.y()}).toList(),
            m.edges.stream().map(e -> new Road(e.from(), e.to(), e.road().name().toLowerCase())).toList(),
            m.merchants.stream().map(p -> new Place(p.id(), p.name(), p.kind(), m.node(p.node()).x(), m.node(p.node()).y())).toList(),
            m.areas,
            xy(m, sim.depot));
    }

    static Snapshot snapshot(Simulation sim, double speed, boolean paused, double shiftStart) {
        List<Courier> couriers = sim.couriers();
        List<Order> orders = sim.orders().stream()
            .sorted(Comparator.comparing((Order o) -> !o.active()).thenComparingDouble(o -> o.promisedBy)).toList();
        double shiftSeconds = Math.max(1, sim.now() - shiftStart);
        int delivered = sim.delivered();
        return new Snapshot(sim.now(), speed, paused, sim.autoDispatch(), sim.demand().name().toLowerCase(),
            couriers.stream().map(c -> courier(sim, c, shiftSeconds)).toList(),
            orders.stream().map(o -> order(sim, o)).toList(),
            new Kpis((int) orders.stream().filter(Order::active).count(),
                (int) orders.stream().filter(o -> o.status() == Order.Status.WAITING).count(),
                delivered,
                delivered == 0 ? 100 : Math.round(100f * sim.deliveredOnTime() / delivered),
                Math.round(sim.averageDeliveryMinutes() * 10) / 10.0,
                (int) couriers.stream().filter(c -> !c.plan().isEmpty()).count()),
            sim.events().stream().limit(30).toList());
    }

    private static CourierView courier(Simulation sim, Courier c, double shiftSeconds) {
        double[] at = c.position(sim.map);
        return new CourierView(c.id, c.name, c.vehicle.name().toLowerCase(), c.vehicle.capacity, c.load(),
            c.status().name().toLowerCase(), c.phone(), round(at[0]), round(at[1]),
            sim.route(c).stream().map(p -> new double[]{round(p[0]), round(p[1])}).toList(),
            stops(sim, c),
            Math.min(1, Math.round(c.busySeconds() / shiftSeconds * 100) / 100.0));
    }

    private static List<StopView> stops(Simulation sim, Courier c) {
        List<Stop> plan = c.plan();
        double[] arrivals = sim.planner().evaluate(c, plan, sim.now()).arrivals();
        return java.util.stream.IntStream.range(0, plan.size()).mapToObj(i -> {
            Stop s = plan.get(i);
            double[] at = xy(sim.map, s.node());
            String label = s.kind() == Stop.Kind.PICKUP ? s.order().merchant.name() : s.order().address;
            return new StopView(s.order().id, s.kind().name().toLowerCase(), label, at[0], at[1], Math.round(arrivals[i]));
        }).toList();
    }

    private static OrderView order(Simulation sim, Order o) {
        return new OrderView(o.id, o.merchant.name(), o.merchant.id(), o.customer, o.address, o.size,
            o.status().name().toLowerCase(), o.courierId(), o.createdAt, o.promisedBy, o.eta(), o.pickedUpAt(),
            o.deliveredAt(), o.late(), o.pinned(), xy(sim.map, o.pickupNode()), xy(sim.map, o.dropoffNode));
    }

    private static double[] xy(CityMap m, int node) {
        return new double[]{m.node(node).x(), m.node(node).y()};
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
