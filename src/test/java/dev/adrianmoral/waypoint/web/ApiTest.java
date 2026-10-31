package dev.adrianmoral.waypoint.web;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.http.Cookie;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ApiTest {

    @Inject
    LiveService live;

    @TestHTTPResource("/")
    URI base;

    static Cookie login(String user, String password) {
        return given().contentType(ContentType.URLENC).formParam("j_username", user).formParam("j_password", password)
            .post("/j_security_check").then().statusCode(200).extract().detailedCookie("quarkus-credential");
    }

    static Cookie dispatcher() {
        return login("dispatcher", "dispatch-test");
    }

    @Test
    void everythingButHealthNeedsASignIn() {
        given().get("/api/state").then().statusCode(401);
        given().get("/api/city").then().statusCode(401);
        given().get("/q/health").then().statusCode(200);
        given().contentType(ContentType.URLENC).formParam("j_username", "dispatcher").formParam("j_password", "nope")
            .post("/j_security_check").then().statusCode(401);
    }

    @Test
    void theSessionCookieIsLockedDown() {
        Cookie c = dispatcher();
        assertTrue(c.isHttpOnly());
        assertEquals("Strict", c.getSameSite(), "SameSite");
        given().cookie(c).get("/api/me").then().statusCode(200).body("role", is("dispatcher"));
    }

    @Test
    void couriersCantDispatch() {
        Cookie courier = login("courier", "courier-test");
        given().cookie(courier).get("/api/me").then().body("role", is("courier"));
        given().cookie(courier).header("X-Requested-With", "waypoint").contentType(ContentType.JSON).body("{\"speed\":30}")
            .put("/api/settings").then().statusCode(403);
        given().cookie(courier).get("/api/report").then().statusCode(403);
    }

    @Test
    void writesNeedTheAppHeader() {
        given().cookie(dispatcher()).contentType(ContentType.JSON).body("{\"speed\":5}")
            .put("/api/settings").then().statusCode(403).body("error", containsString("X-Requested-With"));
    }

    @Test
    void inputIsValidated() {
        Cookie c = dispatcher();
        given().cookie(c).header("X-Requested-With", "waypoint").contentType(ContentType.JSON).body("{\"speed\":1000}")
            .put("/api/settings").then().statusCode(400);
        given().cookie(c).header("X-Requested-With", "waypoint").contentType(ContentType.JSON)
            .body("{\"merchantId\":\"../etc\",\"customer\":\"x\",\"x\":10,\"y\":10,\"size\":1}")
            .post("/api/orders").then().statusCode(400);
        given().cookie(c).header("X-Requested-With", "waypoint").contentType(ContentType.JSON).body("{\"courierId\":\"marco\"}")
            .post("/api/orders/W-999999/assign").then().statusCode(409).body("error", containsString("No order"));
    }

    @Test
    void aDispatcherCreatesAndAssignsAnOrder() {
        Cookie c = dispatcher();
        given().cookie(c).header("X-Requested-With", "waypoint").contentType(ContentType.JSON).body("{\"autoDispatch\":false}")
            .put("/api/settings").then().statusCode(204);
        String shop = given().cookie(c).get("/api/city").then().statusCode(200).extract().path("merchants[0].id");
        String id = given().cookie(c).header("X-Requested-With", "waypoint").contentType(ContentType.JSON)
            .body("{\"merchantId\":\"" + shop + "\",\"customer\":\"Test\",\"x\":3500,\"y\":2400,\"size\":1}")
            .post("/api/orders").then().statusCode(201).extract().path("id");
        given().cookie(c).header("X-Requested-With", "waypoint").contentType(ContentType.JSON).body("{\"courierId\":\"nerea\"}")
            .post("/api/orders/" + id + "/assign").then().statusCode(200).body("courierId", is("nerea"));
        live.tick();
        given().cookie(c).get("/api/state").then().statusCode(200)
            .body("orders.find { it.id == '" + id + "' }.courierId", is("nerea"))
            .body("orders.find { it.id == '" + id + "' }.pinned", is(true));
        given().cookie(c).header("X-Requested-With", "waypoint").contentType(ContentType.JSON).body("{\"autoDispatch\":true}")
            .put("/api/settings").then().statusCode(204);
    }

    @Test
    void theLiveFeedOnlyOpensFromWaypointsOwnPages() throws Exception {
        Cookie c = dispatcher();
        URI ws = URI.create("ws://" + base.getHost() + ":" + base.getPort() + "/ws/live");
        String self = "http://" + base.getHost() + ":" + base.getPort();

        CompletableFuture<String> first = new CompletableFuture<>();
        WebSocket ok = HttpClient.newHttpClient().newWebSocketBuilder()
            .header("Cookie", "quarkus-credential=" + c.getValue()).header("Origin", self)
            .buildAsync(ws, new WebSocket.Listener() {
                StringBuilder text = new StringBuilder();
                @Override
                public CompletableFuture<?> onText(WebSocket s, CharSequence data, boolean last) {
                    text.append(data);
                    if (last) first.complete(text.toString());
                    s.request(1);
                    return null;
                }
            }).get(5, TimeUnit.SECONDS);
        assertTrue(first.get(5, TimeUnit.SECONDS).contains("\"couriers\""));
        ok.abort();

        var evil = HttpClient.newHttpClient().newWebSocketBuilder()
            .header("Cookie", "quarkus-credential=" + c.getValue()).header("Origin", "https://evil.example")
            .buildAsync(ws, new WebSocket.Listener() {});
        assertThrows(CompletionException.class, evil::join);

        var anonymous = HttpClient.newHttpClient().newWebSocketBuilder().header("Origin", self)
            .buildAsync(ws, new WebSocket.Listener() {});
        assertThrows(CompletionException.class, anonymous::join);
    }

    @Test
    void securityHeadersAreSent() {
        given().get("/q/health").then().statusCode(200)
            .header("Content-Security-Policy", containsString("frame-ancestors 'none'"))
            .header("X-Content-Type-Options", "nosniff");
    }
}
