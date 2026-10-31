package dev.adrianmoral.waypoint.fleet;

import dev.adrianmoral.waypoint.city.CityMap;

/** One parcel going from a shop to a customer. Times are simulation seconds since midnight. */
public final class Order {

    public enum Status { WAITING, ASSIGNED, PICKED_UP, DELIVERED }

    public final String id;
    public final CityMap.Place merchant;
    public final String customer;
    public final String address;
    public final int dropoffNode;
    public final int size;
    public final double createdAt;
    public final double promisedBy;

    Status status = Status.WAITING;
    String courierId;
    Double assignedAt, pickedUpAt, deliveredAt;
    Double eta;
    /** Set when a dispatcher chose the courier by hand, so automatic re-planning leaves it alone. */
    boolean pinned;

    public Order(String id, CityMap.Place merchant, String customer, String address, int dropoffNode, int size,
                 double createdAt, double promisedBy) {
        this.id = id;
        this.merchant = merchant;
        this.customer = customer;
        this.address = address;
        this.dropoffNode = dropoffNode;
        this.size = size;
        this.createdAt = createdAt;
        this.promisedBy = promisedBy;
    }

    public int pickupNode() {
        return merchant.node();
    }

    public Status status() { return status; }
    public String courierId() { return courierId; }
    public Double assignedAt() { return assignedAt; }
    public Double pickedUpAt() { return pickedUpAt; }
    public Double deliveredAt() { return deliveredAt; }
    /** Predicted delivery time, the actual one once delivered, or null while nobody has the order. */
    public Double eta() { return deliveredAt != null ? deliveredAt : eta; }
    public boolean late() { return eta() != null && eta() > promisedBy; }
    public boolean pinned() { return pinned; }
    public boolean active() { return status != Status.DELIVERED; }

    void assign(String courierId, double now) {
        this.courierId = courierId;
        if (status == Status.WAITING) {
            status = Status.ASSIGNED;
            assignedAt = now;
        }
    }

    void unassign() {
        courierId = null;
        status = Status.WAITING;
        assignedAt = null;
        eta = null;
        pinned = false;
    }
}
