package io.pipemesh.console.web;

import io.pipemesh.console.demo.DemoService;
import io.pipemesh.console.identity.ConsoleUser;
import io.pipemesh.proto.v1.ExecutionUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Running the example workflow and showing it happen.
 *
 * <p>Server-sent events rather than gRPC, because browsers cannot speak gRPC
 * server streaming. The console re-publishes what it receives, so the browser
 * talks to one thing that already knows who it is — the alternative, a proxy in
 * front of the runtime, would mean a second identity path for the same person
 * (§30.1).
 */
@RestController
@RequestMapping("/api/v1/demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    /** Long enough for a demo to finish; short enough that a wedged one lets go. */
    private static final long TIMEOUT_MILLIS = 120_000;

    private final DemoService demo;
    private final ExecutorService runs = Executors.newVirtualThreadPerTaskExecutor();

    public DemoController(DemoService demo) {
        this.demo = demo;
    }

    public record DemoRequest(Map<String, String> input) {
    }

    @PostMapping(value = "/executions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(ConsoleUser user, @RequestBody(required = false) DemoRequest request) {
        SseEmitter events = new SseEmitter(TIMEOUT_MILLIS);
        Map<String, String> input = request == null || request.input() == null
                ? Map.of() : request.input();

        runs.execute(() -> stream(events, user.organizationId(), input));
        return events;
    }

    private void stream(SseEmitter events, String organizationId, Map<String, String> input) {
        try {
            demo.run(organizationId, input, update -> send(events, update));
            events.complete();
        } catch (RuntimeException failure) {
            // The browser is told rather than left waiting: a stream that stops
            // without saying so looks exactly like a workflow still thinking.
            log.warn("Demo run failed for organization {}", organizationId, failure);
            events.completeWithError(failure);
        }
    }

    private void send(SseEmitter events, ExecutionUpdate update) {
        try {
            events.send(SseEmitter.event()
                    .id(String.valueOf(update.getSequence()))
                    .name(update.getUpdateCase().name())
                    .data(view(update)));
        } catch (IOException disconnected) {
            // The person closed the tab. Turning that into a failure would fill
            // the log with people leaving.
            throw new DemoStreamClosedException(disconnected);
        }
    }

    /** What the screen needs, rather than the whole wire message. */
    private Map<String, Object> view(ExecutionUpdate update) {
        return switch (update.getUpdateCase()) {
            case STEP_STARTED -> Map.of(
                    "stepId", update.getStepStarted().getStepId(),
                    "stepType", update.getStepStarted().getStepType(),
                    "attempt", update.getStepStarted().getAttempt());
            case STEP_FINISHED -> Map.of(
                    "stepId", update.getStepFinished().getStepId(),
                    "outcome", update.getStepFinished().getOutcome().name(),
                    "latencyMs", update.getStepFinished().getLatencyMs());
            case FINISHED -> Map.of("status", update.getFinished().getStatus().name());
            case STARTED -> Map.of("status", update.getStarted().getExecution().getStatus().name());
            case SUSPENDED -> Map.of("stepId", update.getSuspended().getStepId());
            case TOKEN -> Map.of("text", update.getToken().getText());
            default -> Map.of();
        };
    }
}
