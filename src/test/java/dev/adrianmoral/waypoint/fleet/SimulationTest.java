package dev.adrianmoral.waypoint.fleet;

import dev.adrianmoral.waypoint.city.CityGenerator;
import dev.adrianmoral.waypoint.city.CityMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SimulationTest {

    static final CityMap CITY = CityGenerator.generate(7);

    @Test
    void anAfternoonOfDeliveriesMostlyArrivesOnTime() {
        Simulation sim = new Simulation(CITY, 42, 11.5 * 3600);
        for (int i = 0; i < 3 * 3600 / 5; i++) sim.step(5);
        System.out.printf("delivered=%d onTime=%d avg=%.1f active=%d%n", sim.delivered(), sim.deliveredOnTime(),
            sim.averageDeliveryMinutes(), sim.orders().stream().filter(Order::active).count());
        assertTrue(sim.delivered() > 100, "delivered " + sim.delivered());
        assertTrue(sim.deliveredOnTime() >= sim.delivered() * 0.85, "on time " + sim.deliveredOnTime() + "/" + sim.delivered());
        for (Courier c : sim.couriers()) {
            assertTrue(c.load() >= 0 && c.load() <= c.vehicle.capacity, c.name + " load " + c.load());
        }
    }

    @Test
    void aDispatcherCanMoveAnOrderAndItStaysPut() {
        Simulation sim = new Simulation(CITY, 1, 12 * 3600);
        sim.setAutoDispatch(false);
        Order o = sim.createOrder(CITY.merchants.get(0).id(), "Test", 3000, 2400, 1);
        assertEquals(Order.Status.WAITING, o.status());
        sim.assign(o.id, "marco");
        assertEquals("marco", o.courierId());
        assertTrue(o.pinned());
        sim.setAutoDispatch(true);
        for (int i = 0; i < 60; i++) sim.step(5);
        assertEquals("marco", o.courierId(), "automatic re-planning must not undo a dispatcher's choice");
        Simulation.Rejected again = assertThrows(Simulation.Rejected.class, () -> sim.assign(o.id, "marco"));
        assertTrue(again.getMessage().contains("already"));
    }

    @Test
    void onThePhoneAStopWaitsForTheTap() {
        Simulation sim = new Simulation(CITY, 1, 12 * 3600);
        sim.setAutoDispatch(false);
        sim.setPhone("lucia", true);
        Order o = sim.createOrder(CITY.merchants.get(0).id(), "Test", 2000, 600, 1);
        sim.assign(o.id, "lucia");
        Courier lucia = sim.courier("lucia");
        for (int i = 0; i < 2000 && lucia.status() != Courier.Status.WAITING_FOR_PHONE; i++) sim.step(2);
        assertEquals(Courier.Status.WAITING_FOR_PHONE, lucia.status());
        sim.step(600);
        assertEquals(Order.Status.ASSIGNED, o.status(), "nothing happens until the courier taps");
        assertEquals(Stop.Kind.PICKUP, sim.completeStop("lucia").kind());
        assertEquals(Order.Status.PICKED_UP, o.status());
        assertThrows(Simulation.Rejected.class, () -> sim.completeStop("lucia"), "not at the door yet");
    }

    @Test
    void ordersFarFromABikeCanStillBeServedByVans() {
        Simulation sim = new Simulation(CITY, 3, 12 * 3600);
        sim.setDemand(Simulation.Demand.RUSH);
        for (int i = 0; i < 3600 / 5; i++) sim.step(5);
        assertTrue(sim.orders().stream().noneMatch(o -> o.status() == Order.Status.WAITING && sim.now() - o.createdAt > 600),
            "no order waits ten minutes for a courier");
    }
}
