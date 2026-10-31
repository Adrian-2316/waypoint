package dev.adrianmoral.waypoint.store;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** An order as stored: one row per order per shift, updated as it moves from shop to door. */
@Entity
@Table(name = "deliveries", indexes = @Index(name = "deliveries_shift_order", columnList = "shift, orderId", unique = true))
public class DeliveryRecord extends PanacheEntity {
    @Column(nullable = false, length = 36)
    public String shift;
    @Column(nullable = false, length = 16)
    public String orderId;
    @Column(nullable = false)
    public String merchant;
    @Column(nullable = false)
    public String customer;
    @Column(nullable = false)
    public String address;
    @Column(nullable = false, length = 16)
    public String status;
    @Column(length = 16)
    public String courierId;
    public int size;
    /** Simulation clock, seconds since midnight. */
    public double createdAt;
    public double promisedBy;
    public Double pickedUpAt;
    public Double deliveredAt;
    public Boolean onTime;
    @Column(nullable = false)
    public Instant updatedAt;
}
