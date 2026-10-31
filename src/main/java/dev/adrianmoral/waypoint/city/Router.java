package dev.adrianmoral.waypoint.city;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Fastest routes for one kind of vehicle. The city is small (about 200 junctions), so travel times between every
 * pair of junctions are worked out once at start-up; the dispatcher then asks "how long from A to B" thousands of
 * times a second for free.
 */
public final class Router {

    private final CityMap map;
    private final Vehicle vehicle;
    private final double[][] seconds;
    private final int[][] previous;

    public Router(CityMap map, Vehicle vehicle) {
        this.map = map;
        this.vehicle = vehicle;
        int n = map.nodes.size();
        this.seconds = new double[n][];
        this.previous = new int[n][];
        for (int source = 0; source < n; source++) dijkstra(source);
    }

    public Vehicle vehicle() {
        return vehicle;
    }

    /** Travel time in seconds, or infinity when there is no way through. */
    public double seconds(int from, int to) {
        return seconds[from][to];
    }

    /** Junctions from {@code from} to {@code to}, both included. */
    public List<Integer> path(int from, int to) {
        if (Double.isInfinite(seconds[from][to])) throw new IllegalArgumentException("No route from " + from + " to " + to);
        List<Integer> path = new ArrayList<>();
        for (int at = to; at != -1; at = previous[from][at]) path.add(at);
        Collections.reverse(path);
        return path;
    }

    public double edgeSeconds(CityMap.Edge e) {
        double speed = vehicle.speed(e.road());
        return speed == 0 ? Double.POSITIVE_INFINITY : e.length() / speed;
    }

    private void dijkstra(int source) {
        int n = map.nodes.size();
        double[] dist = new double[n];
        int[] prev = new int[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(prev, -1);
        dist[source] = 0;
        PriorityQueue<double[]> queue = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        queue.add(new double[]{0, source});
        while (!queue.isEmpty()) {
            double[] top = queue.poll();
            int u = (int) top[1];
            if (top[0] > dist[u]) continue;
            for (CityMap.Edge e : map.adjacency.get(u)) {
                double t = edgeSeconds(e);
                if (Double.isInfinite(t)) continue;
                int v = e.other(u);
                if (dist[u] + t < dist[v]) {
                    dist[v] = dist[u] + t;
                    prev[v] = u;
                    queue.add(new double[]{dist[v], v});
                }
            }
        }
        seconds[source] = dist;
        previous[source] = prev;
    }
}
