package cn.bitcss.arctra.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Concurrency tests for {@link ExecutionLedger}.
 *
 * <p>Verifies that ledger implementations correctly handle concurrent appends while maintaining
 * sequence uniqueness and monotonicity guarantees.
 *
 * @author lov3r
 */
class ExecutionLedgerConcurrencyTest {

  @Test
  void sameProcessConcurrentAppend_sequencesAreUnique() throws Exception {
    ExecutionLedger ledger = new InMemoryExecutionLedger();
    String processId = "P-concurrent-test";
    int threadCount = 10;
    int recordsPerThread = 100;

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CyclicBarrier barrier = new CyclicBarrier(threadCount);

    List<Future<List<ExecutionRecord>>> futures = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      futures.add(
          executor.submit(
              () -> {
                barrier.await(); // Synchronize start
                List<ExecutionRecord> threadRecords = new ArrayList<>();
                for (int j = 0; j < recordsPerThread; j++) {
                  ExecutionRecord record =
                      ledger.append(processId, EventType.TOOL_EXECUTED, null, null);
                  threadRecords.add(record);
                }
                return threadRecords;
              }));
    }

    // Collect all records
    List<ExecutionRecord> allRecords = new ArrayList<>();
    for (Future<List<ExecutionRecord>> future : futures) {
      allRecords.addAll(future.get());
    }

    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.SECONDS);

    // Verify: all sequences are unique
    Set<Long> sequences =
        allRecords.stream().map(ExecutionRecord::sequence).collect(Collectors.toSet());

    assertThat(sequences).hasSize(threadCount * recordsPerThread);

    // Verify: all recordIds are unique
    Set<String> recordIds =
        allRecords.stream().map(ExecutionRecord::recordId).collect(Collectors.toSet());

    assertThat(recordIds).hasSize(threadCount * recordsPerThread);
  }

  @Test
  void sameProcessConcurrentAppend_sequencesArePositive() throws Exception {
    ExecutionLedger ledger = new InMemoryExecutionLedger();
    String processId = "P-positive-test";
    int threadCount = 5;
    int recordsPerThread = 50;

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CyclicBarrier barrier = new CyclicBarrier(threadCount);

    List<Future<List<ExecutionRecord>>> futures = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      futures.add(
          executor.submit(
              () -> {
                barrier.await();
                List<ExecutionRecord> threadRecords = new ArrayList<>();
                for (int j = 0; j < recordsPerThread; j++) {
                  threadRecords.add(ledger.append(processId, EventType.TOOL_EXECUTED, null, null));
                }
                return threadRecords;
              }));
    }

    List<ExecutionRecord> allRecords = new ArrayList<>();
    for (Future<List<ExecutionRecord>> future : futures) {
      allRecords.addAll(future.get());
    }

    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.SECONDS);

    // Verify: all sequences are positive
    for (ExecutionRecord record : allRecords) {
      assertThat(record.sequence()).isGreaterThanOrEqualTo(1);
    }
  }

  @Test
  void sameProcessConcurrentAppend_queryReturnsStrictlyIncreasingSequences() throws Exception {
    ExecutionLedger ledger = new InMemoryExecutionLedger();
    String processId = "P-ordering-test";
    int threadCount = 8;
    int recordsPerThread = 50;

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CyclicBarrier barrier = new CyclicBarrier(threadCount);

    List<Future<Void>> futures = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      futures.add(
          executor.submit(
              () -> {
                barrier.await();
                for (int j = 0; j < recordsPerThread; j++) {
                  ledger.append(processId, EventType.TOOL_EXECUTED, null, null);
                }
                return null;
              }));
    }

    for (Future<Void> future : futures) {
      future.get();
    }

    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.SECONDS);

    // Query all records
    List<ExecutionRecord> records = ledger.queryByProcess(processId);

    assertThat(records).hasSize(threadCount * recordsPerThread);

    // Verify: sequences are strictly increasing (ordered)
    long previousSequence = 0;
    for (ExecutionRecord record : records) {
      assertThat(record.sequence()).isGreaterThan(previousSequence);
      previousSequence = record.sequence();
    }
  }

  @Test
  void differentProcessesConcurrentAppend_sequencesAreIndependent() throws Exception {
    ExecutionLedger ledger = new InMemoryExecutionLedger();
    int processCount = 5;
    int recordsPerProcess = 50;

    ExecutorService executor = Executors.newFixedThreadPool(processCount);
    CyclicBarrier barrier = new CyclicBarrier(processCount);

    List<Future<String>> futures = new ArrayList<>();
    for (int i = 0; i < processCount; i++) {
      String processId = "P" + i;
      futures.add(
          executor.submit(
              () -> {
                barrier.await();
                for (int j = 0; j < recordsPerProcess; j++) {
                  ledger.append(processId, EventType.TOOL_EXECUTED, null, null);
                }
                return processId;
              }));
    }

    List<String> processIds = new ArrayList<>();
    for (Future<String> future : futures) {
      processIds.add(future.get());
    }

    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.SECONDS);

    // Verify: each process has independent sequence numbering
    for (String processId : processIds) {
      List<ExecutionRecord> records = ledger.queryByProcess(processId);

      assertThat(records).hasSize(recordsPerProcess);
      // Each process should start at sequence 1
      assertThat(records.get(0).sequence()).isEqualTo(1);
    }
  }

  @Test
  void concurrentAppend_recordIdConsistency() throws Exception {
    ExecutionLedger ledger = new InMemoryExecutionLedger();
    String processId = "P-consistency-test";
    int threadCount = 10;
    int recordsPerThread = 100;

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CyclicBarrier barrier = new CyclicBarrier(threadCount);

    List<Future<List<ExecutionRecord>>> futures = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      futures.add(
          executor.submit(
              () -> {
                barrier.await();
                List<ExecutionRecord> threadRecords = new ArrayList<>();
                for (int j = 0; j < recordsPerThread; j++) {
                  threadRecords.add(ledger.append(processId, EventType.TOOL_EXECUTED, null, null));
                }
                return threadRecords;
              }));
    }

    List<ExecutionRecord> allRecords = new ArrayList<>();
    for (Future<List<ExecutionRecord>> future : futures) {
      allRecords.addAll(future.get());
    }

    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.SECONDS);

    // Verify: recordId matches processId:sequence for all records
    for (ExecutionRecord record : allRecords) {
      String expectedRecordId = record.processId() + ":" + record.sequence();
      assertThat(record.recordId()).isEqualTo(expectedRecordId);
    }
  }
}
