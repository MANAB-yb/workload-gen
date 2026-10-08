package com.amalyshev.workload;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Slf4j
public abstract class AbstractWorkload implements Workload {

  /** How long to wait for workload threads to drain once the run duration has elapsed. */
  private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(60);

  protected final NamedParameterJdbcTemplate defaultTemplate;

  /** Parameters of the current run, so {@link #workloadThread} can read its tunables. */
  protected volatile WorkloadParams params;

  protected AbstractWorkload(NamedParameterJdbcTemplate defaultTemplate) {
    this.defaultTemplate = defaultTemplate;
  }

  @Override
  public void run(WorkloadParams params) {
    this.params = params;
    if (params.isRunDDl()) {
      runDdl(params.isDoCleanup());
    }
    if (params.isLoadData()) {
      loadData();
    }
    runWorkload(params);
  }

  public void runWorkload(WorkloadParams params) {
    this.params = params;
    WorkLoadStats stats = new WorkLoadStats();
    ExecutorService executor = Executors.newFixedThreadPool(getNumThreads());
    Instant workloadStart = Instant.now();
    for (int i = 0; i < getNumThreads(); i++) {
      executor.submit(() -> workloadThread(stats));
    }
    while(true) {
      if (workloadStart.plus(params.getDuration()).isBefore(Instant.now())) {
        stats.stopWorkload();
        break;
      }
      log.info("Workload running for {} seconds. Successful operations: {}, failed operations: {}. {}",
          Instant.now().getEpochSecond() - workloadStart.getEpochSecond(),
          stats.statementsSucceeded.get(), stats.statementsFailed.get(), stats.latencySummary());
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
    // shutdownNow interrupts the workers, but a thread parked in a JDBC socket read is not
    // interruptible. Bound the wait instead of polling isTerminated() forever, which would
    // hang the process indefinitely behind a single slow query.
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(SHUTDOWN_GRACE.toSeconds(), TimeUnit.SECONDS)) {
        log.warn("Workload threads still running after {}s, abandoning them",
            SHUTDOWN_GRACE.toSeconds());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    log.info("Workload finished in {} seconds. Successful operations: {}, failed operations: {}. {}",
        Instant.now().getEpochSecond() - workloadStart.getEpochSecond(),
        stats.statementsSucceeded.get(), stats.statementsFailed.get(), stats.latencySummary());
  }

  abstract void runDdl(boolean clean);
  abstract void loadData();
  abstract void workloadThread(WorkLoadStats stats);
  int getNumThreads() {
    return params.getNumThreads();
  }

  public static class WorkLoadStats{
    AtomicBoolean running = new AtomicBoolean(true);
    AtomicInteger statementsSucceeded = new AtomicInteger();
    AtomicInteger statementsFailed = new AtomicInteger();
    private final ConcurrentMap<String, Latency> latencies = new ConcurrentHashMap<>();

    public void incSucceeded() {
      statementsSucceeded.incrementAndGet();
    }

    public void incFailed() {
      statementsFailed.incrementAndGet();
    }

    /** Records how long one statement of the given kind took, for per-kind reporting. */
    public void recordLatency(String kind, long millis) {
      latencies.computeIfAbsent(kind, k -> new Latency()).record(millis);
    }

    /**
     * Renders per-kind latency, so an over-expensive query kind is visible in the periodic
     * progress line rather than only showing up as a stalled operation count.
     */
    public String latencySummary() {
      if (latencies.isEmpty()) {
        return "No statement has completed yet";
      }
      return latencies.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .map(entry -> entry.getKey() + " " + entry.getValue())
          .collect(Collectors.joining(", "));
    }

    public void stopWorkload() {
      running.set(false);
    }
  }

  private static class Latency {
    private final AtomicLong count = new AtomicLong();
    private final AtomicLong totalMillis = new AtomicLong();
    private final AtomicLong maxMillis = new AtomicLong();

    void record(long millis) {
      count.incrementAndGet();
      totalMillis.addAndGet(millis);
      maxMillis.accumulateAndGet(millis, Math::max);
    }

    @Override
    public String toString() {
      long n = count.get();
      return "n=" + n + " avg=" + (n == 0 ? 0 : totalMillis.get() / n) + "ms max=" + maxMillis.get() + "ms";
    }
  }
}
