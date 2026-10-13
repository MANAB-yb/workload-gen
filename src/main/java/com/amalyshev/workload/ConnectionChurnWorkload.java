package com.amalyshev.workload;

import com.google.common.collect.ImmutableMap;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Collections;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ConnectionChurnWorkload extends AbstractWorkload {

  private static final int TABLE_SIZE = 1000000;

  private final String jdbcUrl;
  private final String username;
  private final String password;

  public ConnectionChurnWorkload(
      NamedParameterJdbcTemplate defaultTemplate,
      @Value("${spring.datasource.url}") String jdbcUrl,
      @Value("${spring.datasource.username}") String username,
      @Value("${spring.datasource.password}") String password) {
    super(defaultTemplate);
    this.jdbcUrl = jdbcUrl;
    this.username = username;
    this.password = password;
  }

  @Override
  public void runDdl(boolean clean) {
    if (clean) {
      defaultTemplate.update("DROP TABLE IF EXISTS test_table_conn_churn", Collections.emptyMap());
      log.info("Dropped table test_table_conn_churn");
    }
    defaultTemplate.update(
        "CREATE TABLE IF NOT EXISTS test_table_conn_churn(id bigint PRIMARY KEY, data text)",
        Collections.emptyMap());
    log.info("Created table test_table_conn_churn");
  }

  @Override
  public void loadData() {
  }

  @Override
  void workloadThread(WorkLoadStats stats) {
    Random rnd = new Random();
    Instant lastErrorLog = null;
    while (stats.running.get()) {
      try (Connection conn = DriverManager.getConnection(currentJdbcUrl(), username, password)) {
        int id = rnd.nextInt(TABLE_SIZE);

        try (PreparedStatement insert = conn.prepareStatement(
            "INSERT INTO test_table_conn_churn(id, data) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
          insert.setLong(1, id);
          insert.setString(2, "data" + id);
          insert.executeUpdate();
        }

        try (PreparedStatement select = conn.prepareStatement(
            "SELECT data FROM test_table_conn_churn WHERE id = ?")) {
          select.setLong(1, id);
          try (ResultSet rs = select.executeQuery()) {
            if (rs.next()) {
              rs.getString(1);
            }
          }
        }

        stats.incSucceeded();
      } catch (Exception e) {
        if (lastErrorLog == null || lastErrorLog.plusSeconds(10).isBefore(Instant.now())) {
          log.warn("Failed to execute statement", e);
          lastErrorLog = Instant.now();
        }
        stats.incFailed();
      }
    }
  }

  /** The chain's database when several were named, otherwise the datasource this bean was built with. */
  private String currentJdbcUrl() {
    String routed = ChainDatabase.currentUrl();
    return routed != null ? routed : jdbcUrl;
  }
}
