package io.pipemesh.core.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceContextTest {

    @Test
    void roundTripsThroughTheTraceparentFormat() {
        TraceContext context = TraceContext.generate();

        TraceContext parsed = TraceContext.parse(context.toTraceParent()).orElseThrow();

        assertEquals(context.traceId(), parsed.traceId());
        assertEquals(context.spanId(), parsed.spanId());
        assertEquals(context.sampled(), parsed.sampled());
    }

    @Test
    void generatesIdsOfTheLengthTheFormatRequires() {
        TraceContext context = TraceContext.generate();

        assertEquals(32, context.traceId().length());
        assertEquals(16, context.spanId().length());
    }

    @Test
    void generatesADifferentTraceEachTime() {
        assertNotEquals(TraceContext.generate().traceId(), TraceContext.generate().traceId());
    }

    @Test
    void aChildKeepsTheTraceAndTakesANewSpan() {
        TraceContext parent = TraceContext.generate();
        TraceContext child = parent.child();

        assertEquals(parent.traceId(), child.traceId());
        assertNotEquals(parent.spanId(), child.spanId());
    }

    @Test
    void readsTheSampledFlag() {
        assertTrue(TraceContext.parse(
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01").orElseThrow().sampled());
        assertFalse(TraceContext.parse(
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-00").orElseThrow().sampled());
    }

    @Test
    void ignoresAHeaderItCannotUnderstand() {
        assertTrue(TraceContext.parse(null).isEmpty());
        assertTrue(TraceContext.parse("  ").isEmpty());
        assertTrue(TraceContext.parse("garbage").isEmpty());
        assertTrue(TraceContext.parse("00-tooshort-b7ad6b7169203331-01").isEmpty());
    }
}
