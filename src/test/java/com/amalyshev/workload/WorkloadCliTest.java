package com.amalyshev.workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class WorkloadCliTest {

  private static WorkloadCli parse(String... args) {
    WorkloadCli cli = new WorkloadCli();
    new CommandLine(cli).setCaseInsensitiveEnumValuesAllowed(true).parseArgs(args);
    return cli;
  }

  @Test
  void composesTheJdbcUrlFromHostsAndAppliesTheDefaultPort() {
    assertThat(parse("-H", "a,b:5434,c", "-d", "qbet").jdbcUrl())
        .isEqualTo("jdbc:postgresql://a:5433,b:5434,c:5433/qbet");
  }

  @Test
  void keepsAnExplicitUrlVerbatim() {
    String url = "jdbc:postgresql://host:5433/db?sslmode=verify-full";
    assertThat(parse("--url", url, "-H", "ignored").jdbcUrl()).isEqualTo(url);
  }

  @Test
  void addsSslAndSmartDriverSettingsToTheUrl() {
    assertThat(parse("-H", "node1", "--ssl-mode", "require", "--ssl-root-cert", "/tmp/ca.crt")
        .jdbcUrl())
        .isEqualTo("jdbc:postgresql://node1:5433/yugabyte?sslmode=require&sslrootcert=/tmp/ca.crt");
    assertThat(parse("-H", "node1", "--driver", "yugabytedb", "--topology-keys", "aws.us-east-1.a")
        .jdbcUrl())
        .isEqualTo(
            "jdbc:yugabytedb://node1:5433/yugabyte?load-balance=true&topology-keys=aws.us-east-1.a");
  }

  @Test
  void leavesIpv6LiteralsAlone() {
    assertThat(parse("-H", "[::1]:5433").jdbcUrl())
        .isEqualTo("jdbc:postgresql://[::1]:5433/yugabyte");
    assertThat(parse("-H", "[::1]").jdbcUrl()).isEqualTo("jdbc:postgresql://[::1]:5433/yugabyte");
  }

  @Test
  void translatesConnectionOptionsIntoSpringProperties() {
    WorkloadCli cli = parse("-H", "node1", "-U", "admin", "-W", "secret", "--driver", "yugabytedb",
        "--statement-timeout", "90s", "-o", "reWriteBatchedInserts=false", "-w", "cpu-heavy");
    Map<String, Object> properties = cli.springProperties(cli.chains());
    assertThat(properties)
        .containsEntry("spring.datasource.username", "admin")
        .containsEntry("spring.datasource.password", "secret")
        .containsEntry("spring.datasource.driverClassName", "com.yugabyte.Driver")
        .containsEntry("spring.datasource.hikari.data-source-properties.options",
            "-c statement_timeout=90000")
        .containsEntry("spring.datasource.hikari.data-source-properties.reWriteBatchedInserts",
            "false");
  }

  @Test
  void sizesThePoolForTheWidestStepUnlessItIsGivenExplicitly() {
    WorkloadCli cli = parse("--step", "cpu-heavy:1m:threads=64", "--step", "connection-churn:1m");
    assertThat(cli.poolSize(cli.chains())).isEqualTo(68);
    WorkloadCli explicit = parse("--pool-size", "12", "-w", "cpu-heavy", "-n", "64");
    assertThat(explicit.poolSize(explicit.chains())).isEqualTo(12);
    WorkloadCli parallel = parse("--chain", "cpu-heavy:1m:threads=64",
        "--chain", "connection-churn:1m:threads=10");
    assertThat(parallel.poolSize(parallel.chains())).isEqualTo(82);
  }

  @Test
  void buildsTheChainFromStepsChainAndDefaults() {
    WorkloadCli cli = parse("-t", "5m", "-n", "20", "--step", "cpu-heavy:20m",
        "--chain", "connection-churn:30m,basic-read-write:1m:threads=3");
    List<WorkloadStep> steps = cli.steps();
    assertThat(steps).extracting(WorkloadStep::workload)
        .containsExactly("cpu-heavy", "connection-churn", "basic-read-write");
    assertThat(steps).extracting(step -> step.params().getNumThreads()).containsExactly(20, 20, 3);
    assertThat(WorkloadCli.totalDuration(steps)).isEqualTo(Duration.ofMinutes(51));
  }

  @Test
  void keepsRepeatedChainsSeparateSoTheyCanRunTogether() {
    WorkloadCli cli = parse("-n", "20", "--chain", "cpu-heavy:20m,connection-churn:30m",
        "--chain", "basic-read-write:1m:threads=3");
    List<List<WorkloadStep>> chains = cli.chains();
    assertThat(chains).hasSize(2);
    assertThat(chains.get(0)).extracting(WorkloadStep::workload)
        .containsExactly("cpu-heavy", "connection-churn");
    assertThat(chains.get(0)).extracting(step -> step.params().getNumThreads())
        .containsExactly(20, 20);
    assertThat(chains.get(1)).extracting(WorkloadStep::workload)
        .containsExactly("basic-read-write");
    assertThat(chains.get(1).get(0).params().getNumThreads()).isEqualTo(3);
  }

  @Test
  void usesOneDatabaseForEveryChain() {
    WorkloadCli cli = parse("-d", "qbet", "--chain", "cpu-heavy:1m", "--chain", "connection-churn:1m");
    assertThat(cli.databasesFor(cli.chains())).containsExactly("qbet", "qbet");
    assertThat(cli.springProperties(cli.chains()))
        .containsEntry("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5433/qbet")
        .doesNotContainKey("workload.multi-database");
  }

  @Test
  void assignsEachChainItsOwnDatabase() {
    WorkloadCli cli = parse("-H", "node1", "-d", "db_catalog,db_churn",
        "--chain", "cpu-heavy:1m:threads=64", "--chain", "connection-churn:1m:threads=10");
    assertThat(cli.databasesFor(cli.chains())).containsExactly("db_catalog", "db_churn");
    assertThat(cli.springProperties(cli.chains()))
        .containsEntry("workload.multi-database", "true")
        .containsEntry("workload.database[0].name", "db_catalog")
        .containsEntry("workload.database[1].name", "db_churn")
        .containsEntry("workload.database[0].url", "jdbc:postgresql://node1:5433/db_catalog")
        .containsEntry("workload.database[1].url", "jdbc:postgresql://node1:5433/db_churn")
        .containsEntry("workload.database[0].pool-size", 68)
        .containsEntry("workload.database[1].pool-size", 14)
        .containsEntry("spring.datasource.url", "jdbc:postgresql://node1:5433/db_catalog");
    WorkloadCli repeated = parse("-d", "db_catalog", "-d", "db_churn",
        "--chain", "cpu-heavy:1m", "--chain", "connection-churn:1m");
    assertThat(repeated.databasesFor(repeated.chains())).containsExactly("db_catalog", "db_churn");
  }

  @Test
  void rejectsADatabaseCountThatDoesNotMatchTheChains() {
    WorkloadCli cli = parse("-d", "a,b,c", "--chain", "cpu-heavy:1m", "--chain", "connection-churn:1m");
    assertThatThrownBy(() -> cli.databasesFor(cli.chains()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("3 databases")
        .hasMessageContaining("2 chain");
    WorkloadCli withUrl = parse("--url", "jdbc:postgresql://host/db", "-d", "a,b",
        "--chain", "cpu-heavy:1m", "--chain", "connection-churn:1m");
    assertThatThrownBy(() -> withUrl.springProperties(withUrl.chains()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--url");
  }

  @Test
  void refusesToMixRepeatedChainsWithASingleChainSelection() {
    assertThatThrownBy(() -> parse("--step", "cpu-heavy:1m", "--chain", "a:1m", "--chain", "b:1m")
        .chains())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be combined");
  }

  @Test
  void appliesTheSingleWorkloadDefaultsWhenNoChainIsGiven() {
    WorkloadCli cli = parse("-w", "cpu-heavy", "-t", "20m", "--no-load", "--cleanup");
    List<WorkloadStep> steps = cli.steps();
    assertThat(steps).hasSize(1);
    assertThat(steps.get(0).params().getDuration()).isEqualTo(Duration.ofMinutes(20));
    assertThat(steps.get(0).params().isLoadData()).isFalse();
    assertThat(steps.get(0).params().isDoCleanup()).isTrue();
  }

  @Test
  void appliesEveryBooleanFlagInBothDirections() {
    WorkloadParams on = parse("-w", "cpu-heavy", "--cleanup", "--load", "--ddl").steps()
        .get(0).params();
    assertThat(on.isDoCleanup()).isTrue();
    assertThat(on.isLoadData()).isTrue();
    assertThat(on.isRunDDl()).isTrue();
    WorkloadParams off = parse("-w", "cpu-heavy", "--no-cleanup", "--no-load", "--no-ddl").steps()
        .get(0).params();
    assertThat(off.isDoCleanup()).isFalse();
    assertThat(off.isLoadData()).isFalse();
    assertThat(off.isRunDDl()).isFalse();
  }

  @Test
  void rejectsContradictorySwitches() {
    assertThatThrownBy(() -> parse("-w", "cpu-heavy", "--load", "--no-load").steps())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--load and --no-load contradict");
  }

  @Test
  void refusesAmbiguousOrMissingWorkloadSelection() {
    assertThatThrownBy(() -> parse("-H", "node1").steps())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("No workload selected");
    assertThatThrownBy(() -> parse("-w", "cpu-heavy", "--chain", "cpu-heavy:1m").steps())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be combined");
  }
}
