package com.amalyshev.workload;

import com.google.common.collect.ImmutableMap;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class HotLargeTabletWorkload extends AbstractWorkload {

  private static int TABLE_SIZE = 1000000;
  private static int BATCH_SIZE = 10000;

  public HotLargeTabletWorkload(NamedParameterJdbcTemplate defaultTemplate) {
    super(defaultTemplate);
  }

  @Override
  public void runDdl(boolean clean) {
    if (clean) {
      defaultTemplate.update("DROP TABLE IF EXISTS test_table_large_tablet", Collections.emptyMap());
      log.info("Dropped table test_table_large_tablet");
    }
    defaultTemplate.update("CREATE TABLE IF NOT EXISTS test_table_large_tablet(k bigint, "
        + "v1 text, v2 text, v3 text, v4 text, v5 text, v6 text, v7 text,"
        + " v8 text, v9 text, v10 text, PRIMARY KEY (k ASC)) SPLIT AT VALUES ((10000), (190000))",
        Collections.emptyMap());
    log.info("Created table test_table_large_tablet");
  }

  @Override
  public void loadData() {
    int currentSize =
        defaultTemplate.queryForObject("SELECT COUNT(*) FROM test_table_large_tablet",
            Collections.emptyMap(), Integer.class);
    log.info("test_table_large_tablet current size: {}", currentSize);
    List<Map<String, Object>> paramsBatch = new ArrayList<>();
    for (int i = currentSize; i <= TABLE_SIZE; i++) {
      paramsBatch.add(ImmutableMap.of("id", i, "data", "data" + i));
      if (paramsBatch.size() >= BATCH_SIZE) {
        flushBatch(paramsBatch);
      }
    }
    if (!paramsBatch.isEmpty()) {
      flushBatch(paramsBatch);
    }
  }

  @Override
  void workloadThread(WorkLoadStats stats) {
    Random rnd = new Random();
    Instant lastErrorLog = null;
    while (stats.running.get()) {
      try {
        String query = "/*ddps='wallet-bets-management',dddbs='postgresql',ddh='eu-west-1.0049cbca-93a2-451a-bfb2-dd258a08d6d8.aws.yugabyte.cloud',dddb='qbet',dde='prod',ddpv='2.2.4-wallet-bets-management',traceparent='" + RandomStringUtils.randomAlphabetic(50) + "'*/"
            //+ " SELECT data FROM test_table WHERE id = :id";
           + "SHOW TRANSACTION ISOLATION LEVEL";
        defaultTemplate.queryForObject(query, ImmutableMap.of("id", rnd.nextInt(TABLE_SIZE)), String.class);
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

  private void flushBatch(List<Map<String, Object>> paramsBatch) {
    defaultTemplate.batchUpdate(
        "INSERT INTO test_table_large_tablet(k, v1) values (:id, :data) ON CONFLICT DO NOTHING",
        paramsBatch.toArray(new Map[]{}));
    log.info("Inserted {} entries into test_table_large_tablet", paramsBatch.size());
    paramsBatch.clear();
  }
}     
