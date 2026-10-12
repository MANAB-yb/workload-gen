package com.amalyshev.workload;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Unmatched;

/**
 * Command-line entry point: turns flags into cluster connection settings and into one or more
 * workload chains. Steps inside a chain run in sequence; repeating {@code --chain} runs those
 * chains at the same time.
 *
 * <p>Connection settings are translated into {@code spring.datasource.*} properties before the
 * Spring context starts (see {@link WorkloadLauncher}), because the DataSource is built during
 * startup and cannot be reconfigured afterwards.
 */
@Command(
    name = "workload-gen",
    mixinStandardHelpOptions = true,
    version = "workload-gen 0.0.1-SNAPSHOT",
    sortOptions = false,
    usageHelpAutoWidth = true,
    usageHelpWidth = 100,
    description = "Runs SQL workloads, singly or as a timed chain, against a YugabyteDB "
        + "or PostgreSQL cluster.",
    footerHeading = "%nChain steps:%n",
    footer = {
        "  A step is  <workload>[:<duration>[:<opt>[,<opt>...]]]  and inherits every workload",
        "  option below that it does not override. Step options: threads, duration,",
        "  cpu-amplification (amp), think-time, ddl, cleanup, load. Booleans accept 'key=false'",
        "  or the bare forms 'cleanup' and 'no-load'.",
        "",
        "  Durations accept 30s, 20m, 2h, 1h30m, 500ms, a bare number of seconds, or PT20M.",
        "",
        "Examples:",
        "  workload-gen -H 10.9.143.38,10.9.199.206 -W secret -w cpu-heavy -t 20m",
        "  workload-gen -H node1 -W secret --chain cpu-heavy:20m,connection-churn:30m",
        "  workload-gen -H node1 -W secret --chain cpu-heavy:20m --chain connection-churn:30m",
        "  workload-gen -H node1 --step cpu-heavy:20m --step 'basic-read-write:30m:threads=64,no-load'",
        "  workload-gen --list-workloads",
        ""})
public class WorkloadCli implements Callable<Integer> {

  /** JDBC driver to connect with. The smart driver does its own cluster-aware load balancing. */
  public enum DriverChoice {
    POSTGRESQL("org.postgresql.Driver", "jdbc:postgresql:"),
    YUGABYTEDB("com.yugabyte.Driver", "jdbc:yugabytedb:");

    private final String driverClass;
    private final String urlPrefix;

    DriverChoice(String driverClass, String urlPrefix) {
      this.driverClass = driverClass;
      this.urlPrefix = urlPrefix;
    }

    public String driverClass() {
      return driverClass;
    }

    public String urlPrefix() {
      return urlPrefix;
    }
  }

  @ArgGroup(validate = false, heading = "%nCluster connection:%n")
  final Cluster cluster = new Cluster();

  @ArgGroup(validate = false, heading = "%nWorkload parameters (defaults for every step):%n")
  final Params params = new Params();

  @ArgGroup(validate = false, heading = "%nWorkload selection:%n")
  final Selection selection = new Selection();

  /**
   * Anything the CLI does not recognise is forwarded to Spring Boot, which keeps
   * {@code --spring.datasource.hikari.connectionTimeout=...} style escape hatches available.
   */
  @Unmatched
  List<String> passthrough = new ArrayList<>();

  static class Cluster {
    @Option(names = {"-H", "--host"}, split = ",", paramLabel = "<host[:port]>",
        description = "Cluster host(s), repeatable or comma-separated. Default: ${DEFAULT-VALUE}")
    List<String> hosts = new ArrayList<>(List.of("127.0.0.1"));

    @Option(names = {"-p", "--port"}, paramLabel = "<port>",
        description = "Port for hosts given without one. Default: ${DEFAULT-VALUE}")
    int port = 5433;

    @Option(names = {"-d", "--database"}, paramLabel = "<name>",
        description = "Database name. Default: ${DEFAULT-VALUE}")
    String database = "yugabyte";

    @Option(names = {"-U", "--user", "--username"}, paramLabel = "<user>",
        description = "Database user. Default: ${DEFAULT-VALUE}")
    String username = "yugabyte";

    @Option(names = {"-W", "--password"}, arity = "0..1", interactive = true, paramLabel = "<password>",
        description = "Database password. Pass -W with no value to be prompted, "
            + "or set WORKLOAD_DB_PASSWORD / PGPASSWORD.")
    String password;

    @Option(names = "--url", paramLabel = "<jdbcUrl>",
        description = "Full JDBC URL. Overrides --host/--port/--database and the SSL options.")
    String url;

    @Option(names = "--driver", paramLabel = "<driver>",
        description = "One of: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}")
    DriverChoice driver = DriverChoice.POSTGRESQL;

    @Option(names = "--pool-size", paramLabel = "<n>",
        description = "Hikari maximum pool size. Defaults to the sum of each chain's widest "
            + "step, plus headroom.")
    Integer poolSize;

    @Option(names = "--ssl-mode", paramLabel = "<mode>",
        description = "pgjdbc sslmode, e.g. require or verify-full. Omitted by default.")
    String sslMode;

    @Option(names = "--ssl-root-cert", paramLabel = "<path>",
        description = "Path to the cluster CA certificate, for verify-ca/verify-full.")
    String sslRootCert;

    @Option(names = "--statement-timeout", paramLabel = "<duration>",
        description = "Server-side statement_timeout, so a mis-calibrated query fails fast "
            + "instead of wedging a thread. Default: ${DEFAULT-VALUE}")
    String statementTimeout = "60s";

    @Option(names = "--load-balance",
        description = "Smart-driver cluster-aware load balancing, on by default "
            + "(--driver yugabytedb only).")
    boolean loadBalance;

    @Option(names = "--no-load-balance", description = "Disable smart-driver load balancing.")
    boolean noLoadBalance;

    @Option(names = "--topology-keys", paramLabel = "<keys>",
        description = "Smart-driver topology-keys, e.g. aws.us-east-1.us-east-1a.")
    String topologyKeys;

    @Option(names = {"-o", "--ds-prop"}, paramLabel = "<key=value>",
        description = "Extra JDBC data source property. Repeatable.")
    Map<String, String> dataSourceProperties = new LinkedHashMap<>();
  }

  static class Params {
    @Option(names = {"-n", "--threads"}, paramLabel = "<n>",
        description = "Workload threads. Default: ${DEFAULT-VALUE}")
    int threads = WorkloadParams.DEFAULT_NUM_THREADS;

    @Option(names = {"-t", "--duration"}, paramLabel = "<duration>",
        description = "Run duration per step. Default: 10m")
    String duration;

    @Option(names = "--cpu-amplification", paramLabel = "<n>",
        description = "Computation multiplier per row read by cpu-heavy queries. "
            + "Default: ${DEFAULT-VALUE}")
    int cpuAmplification = WorkloadParams.DEFAULT_CPU_AMPLIFICATION;

    @Option(names = "--think-time", paramLabel = "<millis>",
        description = "Pause between statements in a thread; 0 keeps threads continuously busy. "
            + "Default: ${DEFAULT-VALUE}")
    long thinkTimeMillis = WorkloadParams.DEFAULT_THINK_TIME_MILLIS;

    // Both forms of each switch are declared explicitly rather than with picocli's
    // negatable=true, which sets the value to the OPPOSITE of the declared default when the
    // option is matched: on a default-true option, "--load" would then mean "do not load".
    @Option(names = "--ddl", description = "Create the workload's tables before running (default).")
    boolean ddl;

    @Option(names = "--no-ddl", description = "Skip table creation.")
    boolean noDdl;

    @Option(names = "--cleanup",
        description = "Drop the workload's tables before recreating them. Destroys data. "
            + "Off by default.")
    boolean cleanup;

    @Option(names = "--no-cleanup", description = "Keep existing tables (default).")
    boolean noCleanup;

    @Option(names = "--load",
        description = "Load the workload's seed data before running (default).")
    boolean load;

    @Option(names = "--no-load", description = "Skip the data load.")
    boolean noLoad;
  }

  static class Selection {
    @Option(names = {"-w", "--workload"}, paramLabel = "<name>",
        description = "Single workload to run. See --list-workloads.")
    String workload;

    @Option(names = "--step", paramLabel = "<step>",
        description = "Chain step, repeatable; see 'Chain steps' below.")
    List<String> steps = new ArrayList<>();

    @Option(names = "--chain", paramLabel = "<step,step,...>",
        description = "Comma-separated chain. Repeat to run several chains at the same time; "
            + "steps inside one --chain still run in order.")
    List<String> chain = new ArrayList<>();

    @Option(names = "--chain-file", paramLabel = "<path>",
        description = "File with one chain step per line; '#' starts a comment.")
    Path chainFile;

    @Option(names = "--continue-on-error",
        description = "Run the remaining steps if one fails, instead of stopping the chain.")
    boolean continueOnError;

    @Option(names = "--list-workloads", description = "Print the available workloads and exit.")
    boolean listWorkloads;

    @Option(names = "--dry-run",
        description = "Print the resolved connection settings and chain, then exit without "
            + "touching the cluster.")
    boolean dryRun;
  }

  @Override
  public Integer call() {
    return WorkloadLauncher.launch(this);
  }

  /** Resolves a {@code --x} / {@code --no-x} pair, rejecting the contradictory combination. */
  private static boolean switchValue(String name, boolean positive, boolean negative,
      boolean fallback) {
    if (positive && negative) {
      throw new IllegalArgumentException("--" + name + " and --no-" + name + " contradict");
    }
    if (positive) {
      return true;
    }
    if (negative) {
      return false;
    }
    return fallback;
  }

  /** The parameter set every chain step starts from. */
  WorkloadParams defaultParams() {
    WorkloadParams defaults = new WorkloadParams();
    defaults.setNumThreads(params.threads);
    defaults.setCpuAmplification(params.cpuAmplification);
    defaults.setThinkTimeMillis(params.thinkTimeMillis);
    defaults.setRunDDl(switchValue("ddl", params.ddl, params.noDdl, WorkloadParams.DEFAULT_RUN_DDL));
    defaults.setDoCleanup(
        switchValue("cleanup", params.cleanup, params.noCleanup, WorkloadParams.DEFAULT_DO_CLEANUP));
    defaults.setLoadData(
        switchValue("load", params.load, params.noLoad, WorkloadParams.DEFAULT_LOAD_DATA));
    if (params.duration != null) {
      defaults.setDuration(Durations.parse(params.duration));
    }
    return defaults;
  }

  /**
   * Resolves every chain to run. One {@code --chain} is merged with {@code --step} and
   * {@code --chain-file} into a single sequence. Each additional {@code --chain} is its own
   * chain, and those chains run in parallel.
   */
  List<List<WorkloadStep>> chains() {
    if (selection.chain.size() <= 1) {
      return List.of(steps());
    }
    if (!selection.steps.isEmpty() || selection.chainFile != null || selection.workload != null) {
      throw new IllegalArgumentException(
          "Repeated --chain runs each chain in parallel and cannot be combined with --workload, "
              + "--step, or --chain-file. Put those steps inside one of the --chain values.");
    }
    WorkloadParams defaults = defaultParams();
    List<List<WorkloadStep>> chains = new ArrayList<>();
    for (String chain : selection.chain) {
      chains.add(WorkloadStepParser.parseAll(WorkloadStepParser.splitChain(chain), defaults));
    }
    return chains;
  }

  /**
   * Resolves the single chain from {@code --step}, {@code --chain}, {@code --chain-file} and
   * {@code --workload}, in that order of precedence.
   */
  List<WorkloadStep> steps() {
    if (selection.chain.size() > 1) {
      throw new IllegalArgumentException(
          "More than one --chain was given. Each --chain runs in parallel; read them with chains().");
    }
    WorkloadParams defaults = defaultParams();
    List<String> specs = new ArrayList<>();
    specs.addAll(selection.steps);
    for (String chain : selection.chain) {
      specs.addAll(WorkloadStepParser.splitChain(chain));
    }
    if (selection.chainFile != null) {
      specs.addAll(WorkloadStepParser.readChainFile(selection.chainFile));
    }
    if (specs.isEmpty()) {
      if (selection.workload == null) {
        throw new IllegalArgumentException(
            "No workload selected. Pass --workload <name> for a single run, or --step/--chain/"
                + "--chain-file for a chain. Use --list-workloads to see the available names.");
      }
      return List.of(new WorkloadStep(selection.workload, defaults));
    }
    if (selection.workload != null) {
      throw new IllegalArgumentException(
          "--workload cannot be combined with --step/--chain/--chain-file; put the workload in "
              + "the chain instead.");
    }
    return WorkloadStepParser.parseAll(specs, defaults);
  }

  /** Total wall-clock time of the chain, excluding DDL and data loading. */
  static Duration totalDuration(List<WorkloadStep> steps) {
    Duration total = Duration.ZERO;
    for (WorkloadStep step : steps) {
      total = total.plus(step.params().getDuration());
    }
    return total;
  }

  /**
   * The {@code spring.datasource.*} properties this invocation implies. Applied above
   * application.properties but below explicit {@code --spring.*} arguments.
   */
  Map<String, Object> springProperties(List<List<WorkloadStep>> chains) {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("spring.datasource.url", jdbcUrl());
    properties.put("spring.datasource.driverClassName", cluster.driver.driverClass());
    properties.put("spring.datasource.username", cluster.username);
    properties.put("spring.datasource.password", resolvedPassword());
    properties.put("spring.datasource.hikari.maximumPoolSize", poolSize(chains));
    properties.put("spring.datasource.hikari.data-source-properties.options",
        "-c statement_timeout=" + Durations.parse(cluster.statementTimeout).toMillis());
    cluster.dataSourceProperties.forEach((key, value) ->
        properties.put("spring.datasource.hikari.data-source-properties." + key, value));
    return properties;
  }

  /** Composes the JDBC URL from the host/port/database and SSL options, unless --url was given. */
  String jdbcUrl() {
    if (cluster.url != null && !cluster.url.isBlank()) {
      return cluster.url.trim();
    }
    if (cluster.hosts.isEmpty()) {
      throw new IllegalArgumentException("At least one --host is required");
    }
    StringBuilder url = new StringBuilder(cluster.driver.urlPrefix()).append("//");
    for (int i = 0; i < cluster.hosts.size(); i++) {
      if (i > 0) {
        url.append(',');
      }
      url.append(withPort(cluster.hosts.get(i).trim()));
    }
    url.append('/').append(cluster.database);
    List<String> query = new ArrayList<>();
    if (cluster.sslMode != null) {
      query.add("sslmode=" + cluster.sslMode);
    }
    if (cluster.sslRootCert != null) {
      query.add("sslrootcert=" + cluster.sslRootCert);
    }
    if (cluster.driver == DriverChoice.YUGABYTEDB) {
      query.add("load-balance="
          + switchValue("load-balance", cluster.loadBalance, cluster.noLoadBalance, true));
      if (cluster.topologyKeys != null) {
        query.add("topology-keys=" + cluster.topologyKeys);
      }
    }
    if (!query.isEmpty()) {
      url.append('?').append(String.join("&", query));
    }
    return url.toString();
  }

  private String withPort(String host) {
    if (host.isEmpty()) {
      throw new IllegalArgumentException("Empty host in --host");
    }
    // Leave IPv6 literals and host:port pairs alone; only bare hostnames get the default port.
    boolean hasPort = host.startsWith("[")
        ? host.lastIndexOf(':') > host.lastIndexOf(']')
        : host.indexOf(':') >= 0;
    return hasPort ? host : host + ":" + cluster.port;
  }

  String resolvedPassword() {
    if (cluster.password != null) {
      return cluster.password;
    }
    for (String variable : List.of("WORKLOAD_DB_PASSWORD", "PGPASSWORD")) {
      String value = System.getenv(variable);
      if (value != null && !value.isEmpty()) {
        return value;
      }
    }
    return "";
  }

  /**
   * Sizes the pool for every chain's widest step added together. Chains run at the same time, so
   * a pool sized for only the single widest step would queue the others on the client.
   */
  int poolSize(List<List<WorkloadStep>> chains) {
    if (cluster.poolSize != null) {
      return cluster.poolSize;
    }
    int concurrent = 0;
    for (List<WorkloadStep> steps : chains) {
      concurrent += steps.stream().mapToInt(step -> step.params().getNumThreads()).max().orElse(
          WorkloadParams.DEFAULT_NUM_THREADS);
    }
    // Headroom per chain for its DDL/load connection and the periodic progress queries.
    return Math.max(10, concurrent + 4 * chains.size());
  }
}
