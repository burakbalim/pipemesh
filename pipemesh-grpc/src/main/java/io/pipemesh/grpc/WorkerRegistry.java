package io.pipemesh.grpc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The workers currently connected, and which capabilities each one serves.
 *
 * <p>Routing is scoped to the organization. A worker registered by one
 * organization must never receive another's invocation, and drawing that line
 * later would mean revisiting every routing decision — the same reason the
 * organization was put on the execution row from the first write.
 *
 * <p>Selection is round-robin among the workers that qualify. Load-aware
 * distribution belongs with distributed execution; picking the least busy worker
 * here would be a guess dressed as an optimisation.
 */
public final class WorkerRegistry {

    private final Map<String, ConnectedWorker> workers = new ConcurrentHashMap<>();
    private final AtomicInteger nextWorker = new AtomicInteger();

    public void register(ConnectedWorker worker) {
        workers.put(worker.workerId(), worker);
    }

    void unregister(String workerId, String reason) {
        ConnectedWorker worker = workers.remove(workerId);
        if (worker != null) {
            worker.abandon(reason);
        }
    }

    Optional<ConnectedWorker> find(String workerId) {
        return Optional.ofNullable(workers.get(workerId));
    }

    Optional<ConnectedWorker> pick(String organization, String capabilityId) {
        List<ConnectedWorker> candidates = workers.values().stream()
                .filter(worker -> worker.organization().equals(organization))
                .filter(worker -> worker.serves(capabilityId))
                .toList();

        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        int index = Math.floorMod(nextWorker.getAndIncrement(), candidates.size());
        return Optional.of(candidates.get(index));
    }

    public int size() {
        return workers.size();
    }

    /** For tests and for operators asking "is anything connected at all?". */
    public Optional<String> anyWorkerId() {
        return workers.keySet().stream().findFirst();
    }
}
