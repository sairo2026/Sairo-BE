package com.sairo.be.domain.migration.repository;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Profile("!migrate")
@Repository
public class ImportRowCorrectionJdbcRepository {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final JdbcClient jdbcClient;

  public ImportRowCorrectionJdbcRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Map<Integer, Map<String, String>> findByBatch(long batchId) {
    Map<Integer, Map<String, String>> result = new HashMap<>();
    jdbcClient
        .sql(
            "SELECT source_row_number, corrected_values::text FROM import_row_result"
                + " WHERE batch_id = :batchId AND corrected_values IS NOT NULL")
        .param("batchId", batchId)
        .query(
            (rs, index) -> {
              result.put(rs.getInt(1), parse(rs.getString(2)));
              return 0;
            })
        .list();
    return result;
  }

  public Map<String, String> find(long batchId, int rowNumber) {
    return findByBatch(batchId).getOrDefault(rowNumber, Map.of());
  }

  public void save(long batchId, int rowNumber, Map<String, String> values) {
    ObjectNode node = MAPPER.createObjectNode();
    values.forEach(node::put);
    jdbcClient
        .sql(
            "UPDATE import_row_result SET corrected_values = CAST(:values AS jsonb),"
                + " updated_at = now() WHERE batch_id = :batchId AND source_row_number = :row")
        .param("values", node.toString())
        .param("batchId", batchId)
        .param("row", rowNumber)
        .update();
  }

  private static Map<String, String> parse(String json) {
    Map<String, String> values = new LinkedHashMap<>();
    JsonNode node = MAPPER.readTree(json);
    for (String name : node.propertyNames()) {
      values.put(name, node.get(name).asString());
    }
    return values;
  }
}
