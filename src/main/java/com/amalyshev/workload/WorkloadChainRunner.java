package com.amalyshev.workload;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Runs each chain's {@link WorkloadStep}s one after another. When several chains are given, they
 * run at the same time, so a single invocation can put different kinds of load on the cluster
 * together.
 */
@Component
@Slf4j
public class WorkloadChainRunner {

  private final WorkloadRegistry registry;

  public WorkloadChainRunner(WorkloadRegistry registry) {
    this.registry = registry;
  }

  /**
   * Returns a process exit code: 0 when every step of every chain completed, 1 when any step
   * failed. A failure stops only that chain, unless {@code continueOnError} is set; the other
   * chains keep running.
   */
  public int run(List<List<WorkloadStep>> chains, boolean continueOnError) {
    List<List<Workload>> resolved = new ArrayList<>(chains.size());
    for (List<WorkloadStep> chain : chains) {
      resolved.add(registry.requireAll(chain));
    }
    if (chains.size() == 1) {
      logPlan(chains.get(0), null);
      return runChain(chains.get(0), resolved.get(0), continueOnError, null);
    }
    log.info("Running {} chains in parallel", chains.size());
    for (int i = 0; i < chains.size(); i++) {
      logPlan(chains.get(i), (i + 1) + "/" + chains.size());
    }
    ExecutorService executor = Executors.newFixedThreadPool(chains.size(), runnable -> {
      Thread thread = new Thread(runnable);
      thread.setName("workload-chain");
      return thread;
    });
    try {
      List<Future<Integer>> futures = new ArrayList<>(chains.size());
      for (int i = 0; i < chains.size(); i++) {
        int index = i;
        String label = (i + 1) + "/" + chains.size();
        futures.add(executor.submit(() -> {
          Thread.currentThread().setName("workload-chain-" + (index + 1));
          MDC.put("chain", "[chain " + label + "] ");
          try {
            return runChain(chains.get(index), resolved.get(index), continueOnError, label);
          } finally {
            MDC.remove("chain");
          }
        }));
      }
      int exit = 0;
      for (Future<Integer> future : futures) {
        try {
          exit = Math.max(exit, future.get());
        } catch (ExecutionException e) {
          log.error("Chain failed", e.getCause() == null ? e : e.getCause());
          exit = 1;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          executor.shutdownNow();
          return 1;
        }
      }
      return exit;
    } finally {
      executor.shutdown();
    }
  }

  /** Returns 0 when every step completed, 1 when any step failed. */
  private int runChain(List<WorkloadStep> steps, List<Workload> workloads, boolean continueOnError,
      String chainLabel) {
    Instant chainStart = Instant.now();
    List<String> failed = new ArrayList<>();
    for (int i = 0; i < steps.size(); i++) {
      WorkloadStep step = steps.get(i);
      log.info("=== Step {}/{}: {} ===", i + 1, steps.size(), step);
      Instant stepStart = Instant.now();
      try {
        workloads.get(i).run(step.params());
        log.info("=== Step {}/{} ({}) finished in {} ===", i + 1, steps.size(), step.workload(),
            Durations.format(Duration.between(stepStart, Instant.now())));
      } catch (Exception e) {
        failed.add((i + 1) + ":" + step.workload());
        log.error("=== Step {}/{} ({}) failed after {} ===", i + 1, steps.size(), step.workload(),
            Durations.format(Duration.between(stepStart, Instant.now())), e);
        if (!continueOnError) {
          log.error("Stopping the chain; pass --continue-on-error to run the remaining {} step(s)",
              steps.size() - i - 1);
          break;
        }
      }
    }
    Duration elapsed = Duration.between(chainStart, Instant.now());
    String which = chainLabel == null ? "Chain" : "Chain " + chainLabel;
    if (failed.isEmpty()) {
      log.info("{} complete: {} step(s) in {}", which, steps.size(), Durations.format(elapsed));
      return 0;
    }
    log.error("{} finished with {} failed step(s) [{}] after {}", which, failed.size(),
        String.join(", ", failed), Durations.format(elapsed));
    return 1;
  }

  private void logPlan(List<WorkloadStep> steps, String chainLabel) {
    StringBuilder plan = new StringBuilder(chainLabel == null ? "Workload chain: " : "Chain " + chainLabel + ": ")
        .append(steps.size()).append(" step(s), ")
        .append(Durations.format(WorkloadCli.totalDuration(steps)))
        .append(" of workload time (DDL and data loading are additional)");
    for (int i = 0; i < steps.size(); i++) {
      plan.append(System.lineSeparator()).append("  ").append(i + 1).append(". ").append(steps.get(i));
    }
    log.info(plan.toString());
  }
}
