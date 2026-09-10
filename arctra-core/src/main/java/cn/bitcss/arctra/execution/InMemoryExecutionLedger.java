package cn.bitcss.arctra.execution;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * In-memory execution ledger with atomic sequence allocation.
 *
 * <p>Reference implementation for M6 testing and single-JVM verification. Uses {@link AtomicLong}
 * for per-process sequence allocation and {@link ConcurrentHashMap} for thread-safe storage.
 *
 * <p><strong>NOT for distributed deployment</strong> - state is JVM-local only. For production
 * shared storage, use JDBC or other persistent implementations.
 *
 * <h2>Implementation Notes</h2>
 *
 * <p>This implementation happens to produce gapless sequences (1, 2, 3, ...) under normal operation
 * due to {@link AtomicLong#incrementAndGet()}. However, this is an implementation detail, NOT a
 * contract guarantee. Tests and callers MUST NOT depend on gapless numbering as an interface-level
 * invariant.
 *
 * <p>Production implementations (JDBC, distributed) may have gaps due to transaction rollbacks,
 * failed appends, or distributed allocation.
 *
 * @author lov3r
 * @since M6-T1
 */
public class InMemoryExecutionLedger implements ExecutionLedger {

  // Per-process sequence counters
  private final ConcurrentMap<String, AtomicLong> processSequences = new ConcurrentHashMap<>();

  // All records indexed by recordId
  private final ConcurrentMap<String, ExecutionRecord> records = new ConcurrentHashMap<>();

  @Override
  public ExecutionRecord append(
      String processId, EventType eventType, Long checkpointVersion, String payload) {
    Objects.requireNonNull(processId, "processId cannot be null");
    Objects.requireNonNull(eventType, "eventType cannot be null");

    // Validate checkpointVersion if non-null
    if (checkpointVersion != null && checkpointVersion <= 0) {
      throw new IllegalArgumentException(
          "checkpointVersion must be positive when non-null (got: " + checkpointVersion + ")");
    }

    // Atomically allocate sequence for processId
    long sequence =
        processSequences.computeIfAbsent(processId, k -> new AtomicLong(0)).incrementAndGet();

    // Derive recordId from processId:sequence
    String recordId = processId + ":" + sequence;

    // Create record with current timestamp
    ExecutionRecord record =
        new ExecutionRecord(
            recordId, processId, sequence, eventType, Instant.now(), checkpointVersion, payload);

    // Store record
    records.put(recordId, record);

    return record;
  }

  @Override
  public List<ExecutionRecord> queryByProcess(String processId) {
    Objects.requireNonNull(processId, "processId cannot be null");

    return records.values().stream()
        .filter(r -> r.processId().equals(processId))
        .sorted(Comparator.comparingLong(ExecutionRecord::sequence))
        .collect(Collectors.toList());
  }

  @Override
  public List<ExecutionRecord> queryRecentByProcess(String processId, int limit) {
    Objects.requireNonNull(processId, "processId cannot be null");
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be positive (got: " + limit + ")");
    }

    return records.values().stream()
        .filter(r -> r.processId().equals(processId))
        .sorted(Comparator.comparingLong(ExecutionRecord::sequence).reversed())
        .limit(limit)
        .collect(Collectors.toList());
  }
}
