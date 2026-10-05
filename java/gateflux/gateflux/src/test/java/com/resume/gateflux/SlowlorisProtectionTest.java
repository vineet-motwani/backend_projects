package com.resume.gateflux;

import com.aerospike.client.IAerospikeClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateflux.protection.header-timeout=2s",
                "gateflux.protection.read-timeout=2s",
                "gateflux.protection.request-timeout=4s",
                "gateflux.protection.max-body-size=10KB",
                "server.netty.idle-timeout=2s",
                "server.max-http-request-header-size=8KB"
        }
)
@Import(SlowlorisProtectionTest.TestUploadConfig.class)
class SlowlorisProtectionTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private IAerospikeClient aerospikeClient;

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    @TestConfiguration
    static class TestUploadConfig {

        @RestController
        static class TestUploadController {
            @PostMapping("/test-upload")
            public Mono<String> upload(@RequestBody Mono<String> body) {
                return body.map(b -> "ok:" + b.length());
            }
        }

        @Bean
        @Order(-10)
        public SecurityWebFilterChain testSecurityWebFilterChain(ServerHttpSecurity http) {
            return http
                    .securityMatcher(new PathPatternParserServerWebExchangeMatcher("/test-upload/**"))
                    .csrf(ServerHttpSecurity.CsrfSpec::disable)
                    .authorizeExchange(ex -> ex.anyExchange().permitAll())
                    .build();
        }
    }

    @Test
    void testLegitimateRequestPasses() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            String request = "GET /actuator/health HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();

            String response = readFullResponse(in);
            assertTrue(response.contains("HTTP/1.1 200 OK"), "Expected 200 OK but got: " + response);
            assertTrue(response.contains("\"status\":\"UP\""));
        }
    }

    @Test
    void testSlowlorisHeaderTimeoutClosesConnection() throws Exception {
        long startTime = System.currentTimeMillis();
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(6000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Send partial request headers without the final terminating \r\n\r\n
            out.write("GET /actuator/health HTTP/1.1\r\nHost: localhost\r\nX-Incomplete: slowloris".getBytes(StandardCharsets.UTF_8));
            out.flush();

            // The server should actively terminate the connection after header-timeout (2s)
            int byteRead = in.read();
            long elapsed = System.currentTimeMillis() - startTime;

            assertEquals(-1, byteRead, "Server must close connection on header timeout, returning EOF (-1)");
            assertTrue(elapsed >= 1800 && elapsed <= 5000,
                    "Connection should be closed around the 2s timeout, actual elapsed: " + elapsed + "ms");
        }
    }

    @Test
    void testSlowBodyReadTimeoutClosesConnection() throws Exception {
        long startTime = System.currentTimeMillis();
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(6000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Send headers to /test-upload specifying 1000 bytes, but send only 8 bytes and stall
            String headers = "POST /test-upload HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: 1000\r\n\r\n" +
                    "partial-";
            out.write(headers.getBytes(StandardCharsets.UTF_8));
            out.flush();

            // Netty read-timeout (2s) detects the stalled body upload and terminates the connection
            int byteRead = in.read();
            long elapsed = System.currentTimeMillis() - startTime;

            assertEquals(-1, byteRead, "Server must close connection on stalled body read timeout, returning EOF (-1)");
            assertTrue(elapsed >= 1800 && elapsed <= 5000,
                    "Connection should be closed around the 2s read timeout, actual elapsed: " + elapsed + "ms");
        }
    }

    @Test
    void testContentLengthExceedingMaxBodyRejectedWith413() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Content-Length 20480 bytes exceeds max-body-size of 10KB (10240 bytes)
            String request = "POST /actuator/health HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: 20480\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();

            String response = readFullResponse(in);
            assertTrue(response.contains("413"), "Expected HTTP 413 Payload Too Large but got: " + response);
        }
    }

    @Test
    void testExcessiveHeadersRejected() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Send headers exceeding server.max-http-request-header-size (8KB)
            StringBuilder largeHeaders = new StringBuilder();
            largeHeaders.append("GET /actuator/health HTTP/1.1\r\nHost: localhost\r\n");
            for (int i = 0; i < 150; i++) {
                largeHeaders.append("X-Header-").append(i).append(": ").append("A".repeat(100)).append("\r\n");
            }
            largeHeaders.append("\r\n");

            out.write(largeHeaders.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            String response = readFullResponse(in);
            assertTrue(response.contains("431") || response.contains("400"),
                    "Expected HTTP 431 Request Header Fields Too Large or 400 Bad Request but got: " + response);
        }
    }

    private String readFullResponse(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[1024];
        int nRead;
        while ((nRead = in.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
