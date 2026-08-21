package io.pipemesh.postgres;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock the test moves by hand. Leases are about time passing with nothing
 * happening, which a real clock would make the test wait for.
 */
final class MovableClock extends Clock {

    private Instant instant;

    MovableClock(Instant instant) {
        this.instant = instant;
    }

    void advance(Duration by) {
        instant = instant.plus(by);
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
        return instant;
    }
}
