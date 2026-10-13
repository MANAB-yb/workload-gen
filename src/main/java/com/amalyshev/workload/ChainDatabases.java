package com.amalyshev.workload;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The database assigned to each chain. Empty unless {@code --database} named more than one
 * database, in which case entry {@code i} belongs to chain {@code i}.
 */
@Component
public class ChainDatabases {

  private final List<String> names;
  private final List<String> urls;

  @Autowired
  public ChainDatabases(Environment environment) {
    if (!"true".equals(environment.getProperty("workload.multi-database"))) {
      this.names = List.of();
      this.urls = List.of();
      return;
    }
    List<String> names = new ArrayList<>();
    List<String> urls = new ArrayList<>();
    for (int i = 0; ; i++) {
      String url = environment.getProperty("workload.database[" + i + "].url");
      if (url == null) {
        break;
      }
      urls.add(url);
      names.add(environment.getProperty("workload.database[" + i + "].name", url));
    }
    this.names = List.copyOf(names);
    this.urls = List.copyOf(urls);
  }

  /** Used by tests that run chains without a Spring environment. */
  static ChainDatabases none() {
    return new ChainDatabases(List.of(), List.of());
  }

  private ChainDatabases(List<String> names, List<String> urls) {
    this.names = names;
    this.urls = urls;
  }

  String nameFor(int chainIndex) {
    return names.isEmpty() ? null : names.get(chainIndex);
  }

  String urlFor(int chainIndex) {
    return urls.isEmpty() ? null : urls.get(chainIndex);
  }
}
