package io.github.illuseahashmap.serviceregistry.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OauthClientCredentialsTest {

    @Test
    void reusesATokenUntilThirtySecondsBeforeExpiry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MutableClock clock = new MutableClock(Instant.parse("2026-11-02T00:00:00Z"));
        var oauth = new OauthClientCredentials(request -> {
            calls.incrementAndGet();
            assertEquals(true, request.body().contains("client_secret=s3cret"));
            assertEquals(true, request.body().contains("scope=read"));
            return new TransportResponse(200, "{\"access_token\":\"tok\",\"expires_in\":90}");
        }, clock);
        assertEquals("tok", oauth.accessToken(client(), 1000));
        assertEquals("tok", oauth.accessToken(client(), 1000));
        assertEquals(1, calls.get());
        clock.advance(60);
        assertEquals("tok", oauth.accessToken(client(), 1000));
        assertEquals(2, calls.get());
    }

    @Test
    void tokenFailureIsAnIoFailure() {
        var oauth = new OauthClientCredentials(
                request -> new TransportResponse(401, "{\"error\":\"invalid_client\"}"),
                Clock.systemUTC());
        assertThrows(IOException.class, () -> oauth.accessToken(client(), 1000));
    }

    private static OauthClientCredentials.Client client() {
        return new OauthClientCredentials.Client("https://auth.example/token", "crm", "s3cret", List.of("read"));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
