package io.github.illuseahashmap.serviceregistry.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.illuseahashmap.serviceregistry.domain.HttpMethod;
import io.github.illuseahashmap.serviceregistry.domain.OutboundRequest;
import io.github.illuseahashmap.serviceregistry.domain.TransportResponse;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The transport against a real local HTTP server. */
class JdkHttpTransportTest {

    private HttpServer server;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<byte[]> body = new AtomicReference<>();
    private final AtomicReference<String> header = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            body.set(exchange.getRequestBody().readAllBytes());
            header.set(exchange.getRequestHeaders().getFirst("X-Trace"));
            byte[] response = "{\"ok\":\"đúng\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().add("Set-Cookie", "a=1");
            exchange.getResponseHeaders().add("Set-Cookie", "b=2");
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/items";
    }

    @Test
    void sendsTextBodiesAndHeadersAndReadsTheResponse() throws IOException {
        TransportResponse response = new JdkHttpTransport().send(new OutboundRequest(HttpMethod.POST, url(),
                Map.of("X-Trace", "t-1", "Content-Type", "application/json"), "{\"a\":1}", 2000));

        assertEquals(201, response.status());
        assertEquals("{\"ok\":\"đúng\"}", response.body());
        assertEquals(List.of("a=1", "b=2"), response.setCookies());
        assertEquals("POST", method.get());
        assertEquals("t-1", header.get());
        assertEquals("{\"a\":1}", new String(body.get(), StandardCharsets.UTF_8));
    }

    @Test
    void sendsBinaryBodiesUnchangedAndEmptyBodies() throws IOException {
        byte[] file = {0, (byte) 0xff, 0x10};
        new JdkHttpTransport().send(new OutboundRequest(HttpMethod.PUT, url(), Map.of(), null, 2000, file));
        assertArrayEquals(file, body.get());

        new JdkHttpTransport().send(new OutboundRequest(HttpMethod.GET, url(), Map.of(), null, 2000));
        assertEquals("GET", method.get());
        assertEquals(0, body.get().length);
    }

    @Test
    void reportsRequestsThatCannotBeSent() {
        IOException error = assertThrows(IOException.class, () -> new JdkHttpTransport().send(
                new OutboundRequest(HttpMethod.GET, "http://exa mple/bad uri", Map.of(), null, 2000)));
        assertTrue(error.getMessage().contains("could not be sent"));
    }
}
