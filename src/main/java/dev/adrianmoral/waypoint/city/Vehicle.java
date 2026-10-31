package dev.adrianmoral.waypoint.city;

/** What a courier rides. Bikes can cut through the park; vans are quick on avenues but crawl in the old town. */
public enum Vehicle {
    BIKE(2), VAN(8);

    /** How many parcel units fit at once. */
    public final int capacity;

    Vehicle(int capacity) {
        this.capacity = capacity;
    }

    /** Speed in metres per second on a kind of road, or 0 when the road is closed to this vehicle. */
    public double speed(Road road) {
        double kmh = switch (this) {
            case BIKE -> switch (road) {
                case AVENUE, STREET, BRIDGE -> 18;
                case OLD_TOWN -> 14;
                case PATH -> 16;
            };
            case VAN -> switch (road) {
                case AVENUE -> 40;
                case STREET -> 26;
                case BRIDGE -> 30;
                case OLD_TOWN -> 10;
                case PATH -> 0;
            };
        };
        return kmh / 3.6;
    }
}
