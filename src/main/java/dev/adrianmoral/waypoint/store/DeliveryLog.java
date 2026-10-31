package dev.adrianmoral.waypoint.store;

import dev.adrianmoral.waypoint.fleet.Order;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Writes order changes to the database and answers questions about the shift. */
@ApplicationScoped
public class DeliveryLog {

    private static final Logger LOG = Logger.getLogger(DeliveryLog.class);

    /** Each run of the app is one shift; order numbers start again with every shift. */
    public final String shift = UUID.randomUUID().toString();

    private final EntityManager em;

    public DeliveryLog(EntityManager em) {
        this.em = em;
    }

    public void save(Order o) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                DeliveryRecord r = DeliveryRecord.<DeliveryRecord>find("shift = ?1 and orderId = ?2", shift, o.id).firstResultOptional()
                    .orElseGet(DeliveryRecord::new);
                r.shift = shift;
                r.orderId = o.id;
                r.merchant = o.merchant.name();
                r.customer = o.customer;
                r.address = o.address;
                r.size = o.size;
                r.status = o.status().name();
                r.courierId = o.courierId();
                r.createdAt = o.createdAt;
                r.promisedBy = o.promisedBy;
                r.pickedUpAt = o.pickedUpAt();
                r.deliveredAt = o.deliveredAt();
                r.onTime = o.deliveredAt() == null ? null : o.deliveredAt() <= o.promisedBy;
                r.updatedAt = Instant.now();
                if (!r.isPersistent()) r.persist();
            });
        } catch (RuntimeException e) {
            // The board keeps running on the live state; a lost history row is logged, not fatal.
            LOG.errorf(e, "Couldn't store %s", o.id);
        }
    }

    public record CourierReport(String courierId, long delivered, long onTime, double averageMinutes) {}

    /** Deliveries per courier this shift, straight from the database. */
    public List<CourierReport> report() {
        return QuarkusTransaction.joiningExisting().call(() -> em.createQuery("""
                select r.courierId, count(r), sum(case when r.onTime = true then 1 else 0 end), avg(r.deliveredAt - r.createdAt)
                from DeliveryRecord r
                where r.shift = :shift and r.status = 'DELIVERED'
                group by r.courierId
                order by count(r) desc""", Object[].class)
            .setParameter("shift", shift)
            .getResultList().stream()
            .map(row -> new CourierReport((String) row[0], (Long) row[1], ((Number) row[2]).longValue(),
                Math.round(((Number) row[3]).doubleValue() / 6) / 10.0))
            .toList());
    }
}
