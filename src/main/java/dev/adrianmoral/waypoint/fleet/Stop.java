package dev.adrianmoral.waypoint.fleet;

/** A place a courier has to be: a shop to collect an order, or a door to hand it over. */
public record Stop(Order order, Kind kind) {

    public enum Kind { PICKUP, DROPOFF }

    /** Time spent at the stop: finding the counter or the doorbell, the handover itself. */
    public static final double PICKUP_SECONDS = 120, DROPOFF_SECONDS = 90;

    public int node() {
        return kind == Kind.PICKUP ? order.pickupNode() : order.dropoffNode;
    }

    public double serviceSeconds() {
        return kind == Kind.PICKUP ? PICKUP_SECONDS : DROPOFF_SECONDS;
    }

    /** Parcel units this stop adds to (pickup) or removes from (drop-off) the courier's load. */
    public int loadChange() {
        return kind == Kind.PICKUP ? order.size : -order.size;
    }
}
