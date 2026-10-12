package com.amalyshev.workload;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class WorkloadChainRunnerTest {

  @Test
  void runsRepeatedChainsAtTheSameTime() throws Exception {
    CountDownLatch entered = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    AlphaWorkload.entered = entered;
    AlphaWorkload.release = release;
    BetaWorkload.entered = entered;
    BetaWorkload.release = release;
    WorkloadChainRunner runner = new WorkloadChainRunner(
        new WorkloadRegistry(List.of(new AlphaWorkload(), new BetaWorkload())));
    WorkloadParams params = new WorkloadParams();
    Thread run = new Thread(() -> runner.run(List.of(
        List.of(new WorkloadStep("alpha", params)),
        List.of(new WorkloadStep("beta", params))), false));
    run.start();
    assertThat(entered.await(5, TimeUnit.SECONDS))
        .as("both chains start before either is released")
        .isTrue();
    release.countDown();
    run.join(5_000);
    assertThat(run.isAlive()).isFalse();
  }

  @Test
  void keepsStepsInsideOneChainInOrder() {
    AlphaWorkload.order.setLength(0);
    BetaWorkload.order.setLength(0);
    AlphaWorkload.entered = new CountDownLatch(0);
    BetaWorkload.entered = new CountDownLatch(0);
    AlphaWorkload.release = new CountDownLatch(0);
    BetaWorkload.release = new CountDownLatch(0);
    WorkloadChainRunner runner = new WorkloadChainRunner(
        new WorkloadRegistry(List.of(new AlphaWorkload(), new BetaWorkload())));
    int exit = runner.run(List.of(List.of(
        new WorkloadStep("alpha", new WorkloadParams()),
        new WorkloadStep("beta", new WorkloadParams()))), false);
    assertThat(exit).isZero();
    assertThat(AlphaWorkload.order.toString() + BetaWorkload.order).isEqualTo("ab");
  }

  @Test
  void reportsFailureWithoutStoppingTheOtherChain() throws Exception {
    BetaWorkload.finished.set(false);
    BetaWorkload.entered = new CountDownLatch(1);
    BetaWorkload.release = new CountDownLatch(0);
    WorkloadChainRunner runner = new WorkloadChainRunner(
        new WorkloadRegistry(List.of(new FailingWorkload(), new BetaWorkload())));
    int exit = runner.run(List.of(
        List.of(new WorkloadStep("failing", new WorkloadParams())),
        List.of(new WorkloadStep("beta", new WorkloadParams()))), false);
    assertThat(exit).isEqualTo(1);
    assertThat(BetaWorkload.entered.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(BetaWorkload.finished).isTrue();
  }

  static final class AlphaWorkload implements Workload {
    static volatile CountDownLatch entered = new CountDownLatch(0);
    static volatile CountDownLatch release = new CountDownLatch(0);
    static final StringBuilder order = new StringBuilder();

    @Override
    public void run(WorkloadParams params) {
      order.append('a');
      entered.countDown();
      await(release);
    }
  }

  static final class BetaWorkload implements Workload {
    static volatile CountDownLatch entered = new CountDownLatch(0);
    static volatile CountDownLatch release = new CountDownLatch(0);
    static final StringBuilder order = new StringBuilder();
    static final AtomicBoolean finished = new AtomicBoolean();

    @Override
    public void run(WorkloadParams params) {
      order.append('b');
      entered.countDown();
      await(release);
      finished.set(true);
    }
  }

  static final class FailingWorkload implements Workload {
    @Override
    public void run(WorkloadParams params) {
      throw new IllegalStateException("boom");
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting for the other chain");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
