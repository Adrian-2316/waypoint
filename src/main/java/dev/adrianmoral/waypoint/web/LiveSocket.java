package dev.adrianmoral.waypoint.web;

import io.quarkus.security.Authenticated;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;

/** Pushes a snapshot of the whole board twice a second. Clients only listen; changes go through the REST API. */
@WebSocket(path = "/ws/live")
@Authenticated
public class LiveSocket {

    private final LiveService live;

    public LiveSocket(LiveService live) {
        this.live = live;
    }

    @OnOpen
    public String open() {
        return live.latest();
    }
}
