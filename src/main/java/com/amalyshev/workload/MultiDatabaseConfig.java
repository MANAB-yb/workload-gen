package com.amalyshev.workload;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * One pool per database, selected from the chain running on the current thread. Present only when
 * several databases were named, so a single {@code --database} keeps Spring Boot's own pool.
 */
@Configuration
@ConditionalOnProperty(name = "workload.multi-database", havingValue = "true")
public class MultiDatabaseConfig {

  @Bean
  DataSource dataSource(Environment environment) {
    String username = environment.getProperty("spring.datasource.username");
    String password = environment.getProperty("spring.datasource.password", "");
    String driver = environment.getProperty("spring.datasource.driverClassName");
    Map<String, String> dataSourceProperties = Binder.get(environment)
        .bind("spring.datasource.hikari.data-source-properties",
            Bindable.mapOf(String.class, String.class))
        .orElseGet(Map::of);

    Map<String, HikariDataSource> poolsByUrl = new LinkedHashMap<>();
    Map<String, Integer> sizeByUrl = new LinkedHashMap<>();
    Map<String, String> nameByUrl = new LinkedHashMap<>();
    for (int i = 0; ; i++) {
      String url = environment.getProperty("workload.database[" + i + "].url");
      if (url == null) {
        break;
      }
      String name = environment.getProperty("workload.database[" + i + "].name", "chain-" + i);
      int poolSize = environment.getProperty("workload.database[" + i + "].pool-size", Integer.class, 10);
      sizeByUrl.merge(url, poolSize, Integer::sum);
      nameByUrl.putIfAbsent(url, name);
    }
    if (sizeByUrl.isEmpty()) {
      throw new IllegalStateException(
          "workload.multi-database is set but no workload.database entries were configured");
    }
    String defaultUrl = null;
    Map<Object, Object> targets = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> entry : sizeByUrl.entrySet()) {
      if (defaultUrl == null) {
        defaultUrl = entry.getKey();
      }
      HikariDataSource pool = pool(entry.getKey(), nameByUrl.get(entry.getKey()),
          entry.getValue(), username, password, driver, dataSourceProperties);
      poolsByUrl.put(entry.getKey(), pool);
      targets.put(entry.getKey(), pool);
    }
    RoutingDataSource routing = new RoutingDataSource(new ArrayList<>(poolsByUrl.values()), defaultUrl);
    routing.setTargetDataSources(targets);
    routing.setDefaultTargetDataSource(targets.get(defaultUrl));
    routing.afterPropertiesSet();
    return routing;
  }

  private static HikariDataSource pool(String url, String name, int poolSize, String username,
      String password, String driver, Map<String, String> dataSourceProperties) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(username);
    config.setPassword(password);
    config.setDriverClassName(driver);
    config.setMaximumPoolSize(poolSize);
    config.setPoolName(name);
    dataSourceProperties.forEach(config::addDataSourceProperty);
    return new HikariDataSource(config);
  }

  private static final class RoutingDataSource extends AbstractRoutingDataSource implements DisposableBean {

    private final List<HikariDataSource> pools;
    private final String defaultUrl;

    private RoutingDataSource(List<HikariDataSource> pools, String defaultUrl) {
      this.pools = pools;
      this.defaultUrl = defaultUrl;
    }

    @Override
    protected Object determineCurrentLookupKey() {
      String current = ChainDatabase.currentUrl();
      return current != null ? current : defaultUrl;
    }

    @Override
    public void destroy() {
      pools.forEach(HikariDataSource::close);
    }
  }
}
