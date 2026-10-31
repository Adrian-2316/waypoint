package dev.adrianmoral.waypoint.city;

import java.util.ArrayList;
import java.util.List;

/** The road network of the fictional city of Valdera. Coordinates are metres from the north-west corner. */
public final class CityMap {

    public record Node(int id, double x, double y, String street) {}

    public record Edge(int from, int to, double length, Road road) {
        public int other(int node) {
            return node == from ? to : from;
        }
    }

    /** A named spot on the map: a shop that hands over parcels, or the depot. */
    public record Place(String id, String name, String kind, int node) {}

    public record Area(String kind, List<double[]> outline) {}

    public final double width;
    public final double height;
    public final List<Node> nodes;
    public final List<Edge> edges;
    public final List<List<Edge>> adjacency;
    public final List<Place> merchants;
    public final List<Area> areas;

    public CityMap(double width, double height, List<Node> nodes, List<Edge> edges, List<Place> merchants, List<Area> areas) {
        this.width = width;
        this.height = height;
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
        this.merchants = List.copyOf(merchants);
        this.areas = List.copyOf(areas);
        List<List<Edge>> adj = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) adj.add(new ArrayList<>());
        for (Edge e : edges) {
            adj.get(e.from()).add(e);
            adj.get(e.to()).add(e);
        }
        this.adjacency = adj.stream().map(List::copyOf).toList();
    }

    public Node node(int id) {
        return nodes.get(id);
    }

    public Edge edge(int a, int b) {
        for (Edge e : adjacency.get(a)) if (e.other(a) == b) return e;
        throw new IllegalArgumentException("No road between " + a + " and " + b);
    }

    public int nearestNode(double x, double y) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (Node n : nodes) {
            double d = Math.hypot(n.x() - x, n.y() - y);
            if (d < bestD && !adjacency.get(n.id()).isEmpty()) {
                bestD = d;
                best = n.id();
            }
        }
        return best;
    }
}
