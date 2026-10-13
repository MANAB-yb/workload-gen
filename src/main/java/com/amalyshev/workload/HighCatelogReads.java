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
public class HighCatelogReads extends AbstractWorkload {
    private static String TABLE_NAME = "high_catelog_reads";
    private static int TABLE_SIZE = 1000;
    private static int BATCH_SIZE = 100;
    public HighCatelogReads(NamedParameterJdbcTemplate defaultTemplate) {
        super(defaultTemplate);
    }

    @Override
    public void runDdl(boolean clean) {
        if (clean) {
            defaultTemplate.update("DROP TABLE IF EXISTS " + TABLE_NAME, Collections.emptyMap());
            log.info("Dropped table " + TABLE_NAME);
        }
        defaultTemplate.update("CREATE TABLE IF NOT EXISTS " + TABLE_NAME + "(id bigint PRIMARY KEY, id2 bigint, data text)", Collections.emptyMap());
        log.info("Created table " + TABLE_NAME);
    }
    
    @Override
    public void loadData() {
        // Load some Sample data
        List<Map<String, Object>> paramsBatch = new ArrayList<>();
        for (int i = 0; i < TABLE_SIZE; i++) {
            paramsBatch.add(ImmutableMap.of("id", i, "id2", i, "data", "data" + i));
            if (paramsBatch.size() >= BATCH_SIZE) {
                flushBatch(paramsBatch);
            }
        }
        if (!paramsBatch.isEmpty()) {
            flushBatch(paramsBatch);
        }
    }
    
    @Override
    public void workloadThread(WorkLoadStats stats) {
        // query informatin_schema to produce high catelog reads
        try {
            String query = "SELECT * FROM information_schema.tables WHERE table_schema = 'public' AND table_name = '" + TABLE_NAME + "'";
            List<Map<String, Object>> result = defaultTemplate.queryForList(query, Collections.emptyMap());
            log.info("Found {} tables in information_schema", result.size());
            for (Map<String, Object> row : result) {
                log.info("Table: {}", row.get("table_name"));
            }
            
        } catch (Exception e) {
            log.error("Error querying information_schema", e);
            stats.incFailed();
        }
    }

    private void flushBatch(List<Map<String, Object>> paramsBatch) {
        defaultTemplate.batchUpdate(
            "INSERT INTO " + TABLE_NAME + "(id, id2, data) values (:id, :id2, :data) ON CONFLICT DO NOTHING",
            paramsBatch.toArray(new Map[]{}));
        log.info("Inserted {} entries into high_catelog_reads", paramsBatch.size());
        paramsBatch.clear();
    }

    @Override
    int getNumThreads() {
        // at least 1000 threads to produce enough load for raising anomaly
        return Math.max(params.getNumThreads(), 500); 
    }
}
