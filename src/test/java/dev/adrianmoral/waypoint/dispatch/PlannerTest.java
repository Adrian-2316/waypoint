package dev.adrianmoral.waypoint.dispatch;

import dev.adrianmoral.waypoint.city.CityGenerator;
import dev.adrianmoral.waypoint.city.CityMap;
import dev.adrianmoral.waypoint.city.Router;
import dev.adrianmoral.waypoint.city.Vehicle;
import dev.adrianmoral.waypoint.fleet.Courier;
import dev.adrianmoral.waypoint.fleet.Order;
import dev.adrianmoral.waypoint.fleet.Stop;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlannerTest {

    static final CityMap CITY = CityGenerator.generate(7);
    static final Planner PLANNER = new Planner(CITY, Map.of(Vehicle.BIKE, new Router(CITY, Vehicle.BIKE), Vehicle.VAN, new Router(CITY, Vehicle.VAN)));
    static final double NOW = 12 * 3600;

    static Order order(String id, int shop, int door, int size, double minutesToPromise) {
        CityMap.Place m = CITY.merchants.get(shop);
        int dropoff = CITY.merchants.get(door).node(); // another shop's door is a handy reachable address
        return new Order(id, m, "Test", "Somewhere", dropoff, size, NOW, NOW + minutesToPromise * 60);
    }

    @Test
    void pickupAlwaysComesBeforeDropoff() {
        Courier bike = new Courier("b", "Bea", Vehicle.BIKE, CITY.merchants.get(3).node());
        var fit = PLANNER.bestInsertion(order("W-1", 0, 1, 1, 45), bike, NOW).orElseThrow();
        List<Stop> plan = fit.plan();
        assertEquals(Stop.Kind.PICKUP, plan.get(0).kind());
        assertEquals(Stop.Kind.DROPOFF, plan.get(1).kind());
        assertTrue(fit.timeline().feasible());
    }

    @Test
    void theNearestFreeCourierGetsTheOrder() {
        Order o = order("W-1", 0, 1, 1, 45);
        Courier near = new Courier("near", "Near", Vehicle.BIKE, o.pickupNode());
        Courier far = new Courier("far", "Far", Vehicle.BIKE, CITY.merchants.get(11).node());
        assertEquals("near", PLANNER.bestInsertion(o, List.of(far, near), NOW).orElseThrow().courier().id);
    }

    @Test
    void aBikeNeverCarriesMoreThanItsCapacity() {
        Courier bike = new Courier("b", "Bea", Vehicle.BIKE, CITY.merchants.get(0).node());
        Order large = order("W-2", 0, 1, 2, 60);
        Order small = order("W-3", 0, 2, 1, 60);
        // Plan already holds a large parcel: the small one can only be collected after it is dropped.
        List<Stop> plan = PLANNER.bestInsertion(large, bike, NOW).orElseThrow().plan();
        var timeline = PLANNER.evaluate(bike, List.of(plan.get(0), new Stop(small, Stop.Kind.PICKUP), plan.get(1), new Stop(small, Stop.Kind.DROPOFF)), NOW);
        assertFalse(timeline.feasible(), "three units on a two-unit bike");
        assertFalse(PLANNER.bestInsertion(order("W-4", 0, 1, 3, 60), bike, NOW).isPresent());
    }

    @Test
    void lateDeliveriesCostMoreThanExtraDriving() {
        Courier van = new Courier("v", "Vic", Vehicle.VAN, CITY.merchants.get(0).node());
        Order relaxed = order("W-5", 0, 6, 1, 120);
        Order urgent = order("W-6", 0, 7, 1, 1);
        var a = PLANNER.evaluate(van, List.of(new Stop(relaxed, Stop.Kind.PICKUP), new Stop(relaxed, Stop.Kind.DROPOFF)), NOW);
        var b = PLANNER.evaluate(van, List.of(new Stop(urgent, Stop.Kind.PICKUP), new Stop(urgent, Stop.Kind.DROPOFF)), NOW);
        assertEquals(0, a.lateSeconds());
        assertTrue(b.lateSeconds() > 0);
    }
}
