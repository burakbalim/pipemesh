package io.pipemesh.core.execution;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock the test moves by hand.
 *
 * <p>Recovery is about time passing without anything happening, which a fixed
 * clock cannot express and a real one would make the test wait for.
 */
public final class TestClock extends Clock {

    private Instant instant;

    public TestClock(Instant instant) {
        this.instant = instant;
    }

    public void set(Instant instant) {
        this.instant = instant;
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
