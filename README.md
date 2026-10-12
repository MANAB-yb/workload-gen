# workload-gen

A SQL workload generator for YugabyteDB and PostgreSQL. Each workload is a Java class that creates
its own tables, optionally loads seed data, then drives a fixed number of threads against the
cluster for a fixed duration while reporting throughput and per-statement-kind latency.

Workloads can be run one at a time or chained, so a single command can reproduce a multi-phase
scenario — CPU pressure for 20 minutes, then connection churn for 30 minutes, then back to CPU.

## Build

Requires a JDK 17 or newer; the build targets Java 17 bytecode.

```bash
./gradlew bootJar          # produces build/libs/workload-gen.jar
```

`workload-gen.sh` builds the jar if needed and forwards its arguments to the CLI:

```bash
./workload-gen.sh --help
SKIP_BUILD=1 ./workload-gen.sh --help    # skip the Gradle build
JAVA_OPTS=-Xmx2g ./workload-gen.sh ...   # JVM options, e.g. for a wide thread count
```

## Quick start

```bash
# Single workload
./workload-gen.sh -H 10.9.143.38,10.9.199.206,10.9.87.5 -U yugabyte -W secret \
  -w cpu-heavy -t 20m -n 15

# Chain: CPU-heavy for 20 minutes, then connection churn for 30 minutes
./workload-gen.sh -H 10.9.143.38 -W secret --chain cpu-heavy:20m,connection-churn:30m

# See what a command would do without touching the cluster
./workload-gen.sh -H node1 --chain cpu-heavy:20m,connection-churn:30m --dry-run

./workload-gen.sh --list-workloads
```

## Workloads

| Name | What it does |
| --- | --- |
| `cpu-heavy` | Burns backend CPU with a few bounded queries: chained hashing, `numeric` transcendentals, regex rewriting, top-N sorts, a bounded self cross-join. Cost per query is tuned with `--cpu-amplification`, not by changing query shapes. |
| `connection-churn` | Opens a new physical connection per iteration (outside the pool), runs an insert and a point read, and closes it. |
| `basic-read-write` | Insert plus a secondary-column read against a single table. |
| `multi-table-read-write` | Reads and writes spread over 500 tables, each with a secondary index. |
| `collection-limitation` | 100 indexed tables with weighted table selection, so table *N* gets *N* times the traffic of table 1. |
| `hot-large-tablet` | Range-partitioned table split so that one tablet takes the traffic. |
| `random-hint` | Statements prefixed with a unique tracing comment, to exercise query-statement-cache pressure. |

Names are derived from the class name, so a new `@Component` implementing `Workload` is selectable
immediately. `cpu_heavy` and `CpuHeavyWorkload` resolve to `cpu-heavy` as well.

## Cluster connection

| Option | Meaning |
| --- | --- |
| `-H, --host <host[:port]>` | Cluster host(s), repeatable or comma-separated. Default `127.0.0.1`. |
| `-p, --port <port>` | Port for hosts given without one. Default `5433`. |
| `-d, --database <name>` | Database name. Default `yugabyte`. |
| `-U, --user <user>` | Database user. Default `yugabyte`. |
| `-W, --password [<pw>]` | Password. Pass `-W` with no value to be prompted, or set `WORKLOAD_DB_PASSWORD` / `PGPASSWORD`. |
| `--url <jdbcUrl>` | Full JDBC URL; overrides host/port/database and the SSL options. |
| `--driver postgresql\|yugabytedb` | pgjdbc, or the YugabyteDB smart driver. Default `postgresql`. |
| `--pool-size <n>` | Hikari maximum pool size. Defaults to the widest step's thread count plus headroom, so the pool never becomes the bottleneck. |
| `--ssl-mode <mode>`, `--ssl-root-cert <path>` | `sslmode` and `sslrootcert`, e.g. for YugabyteDB Aeon. |
| `--statement-timeout <duration>` | Server-side `statement_timeout`. Default `60s`. |
| `--load-balance` / `--no-load-balance`, `--topology-keys <keys>` | Smart-driver settings (`--driver yugabytedb`). |
| `-o, --ds-prop <key=value>` | Any extra JDBC data source property. Repeatable. |

These become `spring.datasource.*` properties before the context starts, above
`application.properties` but below an explicit `--spring.<property>=<value>` argument, which is
forwarded to Spring Boot unchanged.

```bash
# YugabyteDB Aeon, smart driver, TLS
./workload-gen.sh --driver yugabytedb -H eu-west-1.abc.aws.yugabyte.cloud \
  -U admin -W --ssl-mode verify-full --ssl-root-cert ~/root.crt \
  -d qbet -w cpu-heavy -t 1h
```

## Workload parameters

Defaults apply to every step in a chain, and any step may override them.

| Option | Default | Meaning |
| --- | --- | --- |
| `-n, --threads <n>` | 15 | Workload threads. |
| `-t, --duration <duration>` | `10m` | Run duration per step. |
| `--cpu-amplification <n>` | 40 | Computation multiplier per row read, for `cpu-heavy`. |
| `--think-time <millis>` | 50 | Pause between statements in a thread; `0` keeps threads continuously busy. |
| `--ddl` / `--no-ddl` | on | Create the workload's tables before running. |
| `--cleanup` / `--no-cleanup` | off | Drop the workload's tables first. **Destroys data.** |
| `--load` / `--no-load` | on | Load the workload's seed data before running. |

Durations accept `30s`, `20m`, `2h`, `1h30m`, `500ms`, a bare number of seconds, or `PT20M`.

## Chains

A step is `<workload>[:<duration>[:<opt>[,<opt>...]]]` and inherits every workload parameter it
does not override. Step options are `threads`, `duration`, `cpu-amplification` (`amp`),
`think-time`, `ddl`, `cleanup`, `load`; booleans accept `key=false` or the bare forms `cleanup`
and `no-load`.

```bash
# Comma-separated shorthand. Steps inside one --chain run in order.
./workload-gen.sh -H node1 -W secret --chain cpu-heavy:20m,connection-churn:30m

# Repeat --chain to run several chains at the same time. A workload can belong to only one of them.
./workload-gen.sh -H node1 -W secret --chain cpu-heavy:20m --chain connection-churn:30m

# One --step per phase, with per-step overrides
./workload-gen.sh -H node1 -W secret \
  --step cpu-heavy:20m:cleanup \
  --step connection-churn:30m:threads=32 \
  --step cpu-heavy:10m:no-ddl,no-load,amp=80

# From a file, one step per line
./workload-gen.sh -H node1 -W secret --chain-file chains/cpu-then-churn.chain
```

Workload names are checked before anything connects, so a typo in the last step of a 90-minute
chain is reported immediately. A failing step stops the chain unless `--continue-on-error` is
given; the exit code is `0` when every step completed, `1` when any step failed, and `2` for bad
arguments.

Reusing a table across steps is the reason the DDL and load flags are per-step: load once on the
first step, then `no-ddl,no-load` on later steps that read the same data.

## Notes on `cpu-heavy`

The goal is to consume a lot of server CPU with a small number of queries that all *complete*.
Every query reads a small bounded slice of the table and then amplifies the computation performed
per row, so cost lands on backend CPU rather than on distributed reads and stays proportional to
the slice instead of the table size. Raise `--cpu-amplification` to burn more CPU per query; lower
it if per-query latency starts approaching `--statement-timeout`.

The table's primary key is range-partitioned (`PRIMARY KEY (id ASC)`) on purpose: YugabyteDB's
default hash partitioning cannot serve the bounded `id >= ? AND id < ?` predicate, and every slice
would silently degrade into a full scan with a storage filter.
