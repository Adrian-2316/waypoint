package dev.adrianmoral.waypoint.city;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RouterTest {

    static final CityMap CITY = CityGenerator.generate(7);
    static final Router BIKE = new Router(CITY, Vehicle.BIKE);
    static final Router VAN = new Router(CITY, Vehicle.VAN);

    @Test
    void theSameSeedBuildsTheSameCity() {
        CityMap again = CityGenerator.generate(7);
        assertEquals(CITY.nodes, again.nodes);
        assertEquals(CITY.edges, again.edges);
        assertEquals(12, CITY.merchants.size());
    }

    @Test
    void everyShopCanReachEveryOtherShopByVan() {
        for (CityMap.Place a : CITY.merchants) {
            for (CityMap.Place b : CITY.merchants) {
                assertTrue(Double.isFinite(VAN.seconds(a.node(), b.node())), a.name() + " → " + b.name());
            }
        }
    }

    @Test
    void crossingTheRiverAlwaysUsesABridge() {
        CityMap.Node north = CITY.nodes.stream().filter(n -> n.y() < 300 && !CITY.adjacency.get(n.id()).isEmpty()).findFirst().orElseThrow();
        CityMap.Node south = CITY.nodes.stream().filter(n -> n.y() > 2500 && !CITY.adjacency.get(n.id()).isEmpty()).findFirst().orElseThrow();
        List<Integer> path = VAN.path(north.id(), south.id());
        boolean bridge = false;
        for (int i = 1; i < path.size(); i++) bridge |= CITY.edge(path.get(i - 1), path.get(i)).road() == Road.BRIDGE;
        assertTrue(bridge, "route " + path);
    }

    @Test
    void vansStayOutOfTheParkButBikesCutThrough() {
        CityMap.Edge path = CITY.edges.stream().filter(e -> e.road() == Road.PATH).findFirst().orElseThrow();
        assertTrue(Double.isInfinite(VAN.edgeSeconds(path)));
        assertTrue(Double.isFinite(BIKE.edgeSeconds(path)));
        for (int i = 0; i < CITY.merchants.size(); i++) {
            List<Integer> route = VAN.path(CITY.merchants.get(i).node(), CITY.merchants.get((i + 5) % 12).node());
            for (int j = 1; j < route.size(); j++) assertNotEquals(Road.PATH, CITY.edge(route.get(j - 1), route.get(j)).road());
        }
    }

    @Test
    void travelTimesAreSymmetricAndObeyTheTriangleRule() {
        int a = CITY.merchants.get(0).node(), b = CITY.merchants.get(1).node(), c = CITY.merchants.get(2).node();
        assertEquals(VAN.seconds(a, b), VAN.seconds(b, a), 1e-6);
        assertTrue(VAN.seconds(a, c) <= VAN.seconds(a, b) + VAN.seconds(b, c) + 1e-6);
    }
}
