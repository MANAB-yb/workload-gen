package com.amalyshev.workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorkloadRegistryTest {

  private static final WorkloadRegistry REGISTRY =
      new WorkloadRegistry(List.of(new CpuHeavyWorkload(null), new BasicReadWriteWorkload(null)));

  @Test
  void derivesKebabCaseNamesFromTheClassNames() {
    assertThat(REGISTRY.names()).containsExactly("basic-read-write", "cpu-heavy");
  }

  @Test
  void resolvesTheAlternativeSpellingsAUserIsLikelyToType() {
    assertThat(REGISTRY.require("cpu-heavy")).isInstanceOf(CpuHeavyWorkload.class);
    assertThat(REGISTRY.require("cpu_heavy")).isInstanceOf(CpuHeavyWorkload.class);
    assertThat(REGISTRY.require("CpuHeavy")).isInstanceOf(CpuHeavyWorkload.class);
    assertThat(REGISTRY.require("CpuHeavyWorkload")).isInstanceOf(CpuHeavyWorkload.class);
    assertThat(REGISTRY.require(" cpu-heavy ")).isInstanceOf(CpuHeavyWorkload.class);
  }

  @Test
  void listsTheKnownNamesWhenOneIsNotRecognised() {
    assertThatThrownBy(() -> REGISTRY.require("cpu-heavey"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown workload 'cpu-heavey'")
        .hasMessageContaining("cpu-heavy");
  }

  @Test
  void discoversEveryWorkloadOnTheClasspathWithoutADatabase() {
    // --list-workloads and the CLI's pre-flight name check both rely on this, because resolving
    // names from a Spring context would need a reachable cluster.
    assertThat(WorkloadRegistry.discoverNames())
        .contains("cpu-heavy", "connection-churn", "basic-read-write", "collection-limitation",
            "hot-large-tablet", "multi-table-read-write", "random-hint");
  }

  @Test
  void rejectsAWorkloadSharedByParallelChains() {
    WorkloadParams params = new WorkloadParams();
    assertThatThrownBy(() -> WorkloadRegistry.requireDistinct(List.of(
        List.of(new WorkloadStep("cpu-heavy", params), new WorkloadStep("connection-churn", params)),
        List.of(new WorkloadStep("cpu_heavy", params)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cpu_heavy")
        .hasMessageContaining("chain 1")
        .hasMessageContaining("chain 2");
  }

  @Test
  void checksChainStepNamesBeforeAnythingConnects() {
    WorkloadParams params = new WorkloadParams();
    WorkloadRegistry.requireKnown(List.of(new WorkloadStep("cpu-heavy", params)));
    assertThatThrownBy(
        () -> WorkloadRegistry.requireKnown(List.of(new WorkloadStep("nope", params))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown workload 'nope'");
  }
}
