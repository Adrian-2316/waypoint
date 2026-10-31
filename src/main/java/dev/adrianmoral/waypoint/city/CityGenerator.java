package dev.adrianmoral.waypoint.city;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Builds Valdera: a street grid with a river (crossed only at three bridges), an old town of slow, narrow lanes,
 * a park that only bikes can cut through, and two fast avenues. The same seed always gives the same city.
 */
public final class CityGenerator {

    static final double STEP = 250, WIDTH = 4000, HEIGHT = 2750;
    static final int COLS = 17, ROWS = 12;
    static final int AVENUE_ROW = 4, AVENUE_COL = 8;
    static final int[] BRIDGE_COLS = {3, 8, 13};
    static final double RIVER_HALF_WIDTH = 60, RIVER_CLEARANCE = 105;
    static final double OLD_TOWN_X = 2650, OLD_TOWN_Y = 760, OLD_TOWN_R = 470;
    static final double[] PARK = {540, 410, 1210, 960}; // x1, y1, x2, y2

    static final String[] ROW_STREETS = {"Calle del Norte", "Calle Olmo", "Calle Laurel", "Calle San Juan",
        "Avenida de la Paz", "Calle Mayor", "Calle del Puente", "Calle Ribera", "Calle Huertas", "Calle Sol",
        "Calle Molinos", "Calle del Sur"};
    static final String[] COL_STREETS = {"Calle Oeste", "Calle Almendro", "Calle Prado", "Calle Lagar", "Calle Cerezo",
        "Calle Tejares", "Calle Pozo", "Calle Mercado", "Gran Vía", "Calle Rúa Vieja", "Calle Portales",
        "Calle Barriocepo", "Calle Muro", "Calle Vara de Rey", "Calle Labradores", "Calle Pinar", "Calle Este"};

    static final String[][] MERCHANTS = {
        {"horno", "Horno Santa Ana", "bakery"}, {"farmacia", "Farmacia Ribera", "pharmacy"},
        {"huerta", "La Huerta", "grocer"}, {"flores", "Flores Olmo", "florist"},
        {"libreria", "Librería Puente", "books"}, {"northwind", "Northwind Coffee", "cafe"},
        {"ferreteria", "Ferretería Norte", "hardware"}, {"kaizen", "Sushi Kaizen", "restaurant"},
        {"bruno", "Pizzería Bruno", "restaurant"}, {"laurel", "Vinoteca Laurel", "wine"},
        {"mimo", "Pastelería Mimo", "bakery"}, {"optica", "Óptica Sol", "optician"},
    };

    public static double riverY(double x) {
        return 1480 + 200 * Math.sin(x / 650 + 0.6);
    }

    public static CityMap generate(long seed) {
        Random rnd = new Random(seed);

        // 1. Grid points, nudged so the city doesn't look ruled. Avenues stay straight.
        int[][] index = new int[COLS][ROWS];
        for (int[] col : index) Arrays.fill(col, -1);
        List<CityMap.Node> nodes = new ArrayList<>();
        for (int c = 0; c < COLS; c++) {
            for (int r = 0; r < ROWS; r++) {
                double x = c * STEP + (c == AVENUE_COL || c == 0 || c == COLS - 1 ? 0 : jitter(rnd));
                double y = r * STEP + (r == AVENUE_ROW || r == 0 || r == ROWS - 1 ? 0 : jitter(rnd));
                x = Math.min(WIDTH, Math.max(0, x));
                if (Math.abs(y - riverY(x)) < RIVER_CLEARANCE) continue; // in the water
                String street = c == AVENUE_COL ? COL_STREETS[c] : ROW_STREETS[r];
                index[c][r] = nodes.size();
                nodes.add(new CityMap.Node(nodes.size(), round(x), round(y), street));
            }
        }

        // 2. Streets between grid neighbours on the same bank; across the river only at bridges.
        List<CityMap.Edge> edges = new ArrayList<>();
        for (int c = 0; c < COLS; c++) {
            final int col = c;
            boolean bridge = Arrays.stream(BRIDGE_COLS).anyMatch(b -> b == col);
            for (int r = 0; r < ROWS; r++) {
                if (index[c][r] < 0) continue;
                if (c + 1 < COLS && index[c + 1][r] >= 0) addStreet(nodes, edges, index[c][r], index[c + 1][r], r == AVENUE_ROW, false);
                // Next node down the column, which may be across the river.
                for (int r2 = r + 1; r2 < ROWS; r2++) {
                    if (index[c][r2] < 0) continue;
                    addStreet(nodes, edges, index[c][r], index[c][r2], c == AVENUE_COL, bridge);
                    break;
                }
            }
        }

        // 3. Old town lanes: extra diagonal alleys.
        for (int c = 0; c + 1 < COLS; c++) {
            for (int r = 0; r + 1 < ROWS; r++) {
                int a = index[c][r], b = index[c + 1][r + 1];
                if (a >= 0 && b >= 0 && inOldTown(nodes.get(a)) && inOldTown(nodes.get(b)) && rnd.nextBoolean()) {
                    edges.add(edge(nodes, a, b, Road.OLD_TOWN));
                }
            }
        }

        // 4. Close a few streets to break the grid, but never cut the city in two.
        List<CityMap.Edge> streets = new ArrayList<>(edges.stream().filter(e -> e.road() == Road.STREET).toList());
        Collections.shuffle(streets, rnd);
        int toClose = (int) (streets.size() * 0.08);
        for (CityMap.Edge e : streets) {
            if (toClose == 0) break;
            edges.remove(e);
            if (connectedForVans(nodes.size(), edges)) toClose--;
            else edges.add(e);
        }

        // 5. Shops that hand over parcels, spread across the city on streets vans can reach.
        List<CityMap.Place> merchants = new ArrayList<>();
        List<CityMap.Node> candidates = new ArrayList<>(nodes);
        Collections.shuffle(candidates, rnd);
        Map<Integer, List<CityMap.Edge>> adj = adjacency(edges);
        int next = 0;
        for (CityMap.Node n : candidates) {
            if (next == MERCHANTS.length) break;
            boolean vanReachable = adj.getOrDefault(n.id(), List.of()).stream().anyMatch(e -> e.road() != Road.PATH);
            boolean spaced = merchants.stream().allMatch(m -> dist(nodes.get(m.node()), n) > 650);
            if (vanReachable && spaced && n.x() > 150 && n.x() < WIDTH - 150 && n.y() > 150 && n.y() < HEIGHT - 150) {
                String[] m = MERCHANTS[next++];
                merchants.add(new CityMap.Place(m[0], m[1], m[2], n.id()));
            }
        }

        return new CityMap(WIDTH, HEIGHT, nodes, edges, merchants, areas());
    }

    /** The depot where couriers start their shift: the junction nearest the centre of the north bank. */
    public static int depot(CityMap map) {
        return map.nearestNode(2000, 1000);
    }

    private static void addStreet(List<CityMap.Node> nodes, List<CityMap.Edge> edges, int a, int b, boolean avenue, boolean bridgeCol) {
        CityMap.Node na = nodes.get(a), nb = nodes.get(b);
        boolean crossesRiver = Math.signum(na.y() - riverY(na.x())) != Math.signum(nb.y() - riverY(nb.x()));
        if (crossesRiver && !bridgeCol) return;
        Road road;
        if (crossesRiver) road = Road.BRIDGE;
        else if (avenue) road = Road.AVENUE;
        else if (inPark((na.x() + nb.x()) / 2, (na.y() + nb.y()) / 2)) road = Road.PATH;
        else if (inOldTown(na) && inOldTown(nb)) road = Road.OLD_TOWN;
        else road = Road.STREET;
        edges.add(edge(nodes, a, b, road));
    }

    private static CityMap.Edge edge(List<CityMap.Node> nodes, int a, int b, Road road) {
        return new CityMap.Edge(a, b, round(dist(nodes.get(a), nodes.get(b))), road);
    }

    private static boolean connectedForVans(int n, List<CityMap.Edge> edges) {
        Map<Integer, List<CityMap.Edge>> adj = adjacency(edges);
        Integer start = adj.keySet().stream().findFirst().orElse(null);
        if (start == null) return false;
        boolean[] seen = new boolean[n];
        ArrayDeque<Integer> queue = new ArrayDeque<>(List.of(start));
        seen[start] = true;
        int count = 1;
        while (!queue.isEmpty()) {
            int u = queue.poll();
            for (CityMap.Edge e : adj.getOrDefault(u, List.of())) {
                int v = e.other(u);
                if (!seen[v]) {
                    seen[v] = true;
                    count++;
                    queue.add(v);
                }
            }
        }
        // Every junction with a road must be reachable; vans additionally must reach every non-park junction.
        long withRoads = adj.keySet().size();
        if (count != withRoads) return false;
        return vansReachEveryStreet(n, adj);
    }

    private static boolean vansReachEveryStreet(int n, Map<Integer, List<CityMap.Edge>> adj) {
        List<Integer> streetNodes = adj.entrySet().stream()
            .filter(en -> en.getValue().stream().anyMatch(e -> e.road() != Road.PATH)).map(Map.Entry::getKey).toList();
        if (streetNodes.isEmpty()) return false;
        boolean[] seen = new boolean[n];
        ArrayDeque<Integer> queue = new ArrayDeque<>(List.of(streetNodes.get(0)));
        seen[streetNodes.get(0)] = true;
        while (!queue.isEmpty()) {
            int u = queue.poll();
            for (CityMap.Edge e : adj.get(u)) {
                if (e.road() == Road.PATH) continue;
                int v = e.other(u);
                if (!seen[v]) {
                    seen[v] = true;
                    queue.add(v);
                }
            }
        }
        return streetNodes.stream().allMatch(i -> seen[i]);
    }

    private static Map<Integer, List<CityMap.Edge>> adjacency(List<CityMap.Edge> edges) {
        Map<Integer, List<CityMap.Edge>> adj = new HashMap<>();
        for (CityMap.Edge e : edges) {
            adj.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
            adj.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e);
        }
        return adj;
    }

    private static List<CityMap.Area> areas() {
        List<double[]> north = new ArrayList<>(), south = new ArrayList<>();
        for (double x = -50; x <= WIDTH + 50; x += 50) {
            north.add(new double[]{x, round(riverY(x) - RIVER_HALF_WIDTH)});
            south.add(0, new double[]{x, round(riverY(x) + RIVER_HALF_WIDTH)});
        }
        List<double[]> river = new ArrayList<>(north);
        river.addAll(south);

        List<double[]> park = List.of(new double[]{PARK[0], PARK[1]}, new double[]{PARK[2], PARK[1]},
            new double[]{PARK[2], PARK[3]}, new double[]{PARK[0], PARK[3]});

        List<double[]> oldTown = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            double a = i * Math.PI * 2 / 36;
            oldTown.add(new double[]{round(OLD_TOWN_X + Math.cos(a) * (OLD_TOWN_R + 40)), round(OLD_TOWN_Y + Math.sin(a) * (OLD_TOWN_R + 40))});
        }
        return List.of(new CityMap.Area("river", river), new CityMap.Area("park", park), new CityMap.Area("oldtown", oldTown));
    }

    static boolean inPark(double x, double y) {
        return x > PARK[0] && x < PARK[2] && y > PARK[1] && y < PARK[3];
    }

    static boolean inOldTown(CityMap.Node n) {
        return Math.hypot(n.x() - OLD_TOWN_X, n.y() - OLD_TOWN_Y) <= OLD_TOWN_R;
    }

    private static double jitter(Random rnd) {
        return (rnd.nextDouble() - 0.5) * 70;
    }

    private static double dist(CityMap.Node a, CityMap.Node b) {
        return Math.hypot(a.x() - b.x(), a.y() - b.y());
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
