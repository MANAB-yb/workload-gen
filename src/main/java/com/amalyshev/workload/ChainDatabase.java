package com.amalyshev.workload;

/**
 * JDBC URL of the chain running on this thread. Workload threads inherit it, so a chain aimed at
 * its own database keeps using that database after it hands work to its pool.
 */
final class ChainDatabase {

  private static final InheritableThreadLocal<String> URL = new InheritableThreadLocal<>();

  private ChainDatabase() {
  }

  static void use(String jdbcUrl) {
    URL.set(jdbcUrl);
  }

  static void clear() {
    URL.remove();
  }

  /** The chain's URL, or {@code null} when this run uses the single shared datasource. */
  static String currentUrl() {
    return URL.get();
  }
}
