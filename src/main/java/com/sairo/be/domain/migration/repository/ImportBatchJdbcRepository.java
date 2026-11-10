package com.sairo.be.domain.migration.repository;

import com.sairo.be.domain.migration.entity.ImportNotCancelledItem;
import com.sairo.be.domain.migration.entity.ImportSheetInfo;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Profile("!migrate")
@Repository
public class ImportBatchJdbcRepository {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final JdbcClient jdbcClient;

  public ImportBatchJdbcRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public void saveSheets(long batchId, List<ImportSheetInfo> sheets) {
    ArrayNode array = MAPPER.createArrayNode();
    for (ImportSheetInfo sheet : sheets) {
      ObjectNode node = array.addObject();
      node.put("name", sheet.name());
      node.put("row_count", sheet.rowCount());
      node.put("column_count", sheet.columnCount());
    }
    jdbcClient
        .sql("UPDATE import_batch SET sheets = CAST(:sheets AS jsonb) WHERE id = :id")
        .param("sheets", array.toString())
        .param("id", batchId)
        .update();
  }

  public List<ImportSheetInfo> findSheets(long batchId) {
    String json =
        jdbcClient
            .sql("SELECT sheets::text FROM import_batch WHERE id = :id")
            .param("id", batchId)
            .query(String.class)
            .optional()
            .orElse(null);
    List<ImportSheetInfo> sheets = new ArrayList<>();
    if (json == null) {
      return sheets;
    }
    for (JsonNode node : MAPPER.readTree(json)) {
      sheets.add(
          new ImportSheetInfo(
              node.get("name").asString(),
              node.get("row_count").asInt(),
              node.get("column_count").asInt()));
    }
    return sheets;
  }

  public void markPreviewed(long batchId) {
    jdbcClient
        .sql("UPDATE import_batch SET status = 'PREVIEW' WHERE id = :id AND status = 'MAPPING'")
        .param("id", batchId)
        .update();
  }

  public void markCancelled(
      long batchId, Instant cancelledAt, int cancelledCount, List<ImportNotCancelledItem> items) {
    ArrayNode array = MAPPER.createArrayNode();
    for (ImportNotCancelledItem item : items) {
      ObjectNode node = array.addObject();
      node.put("entityType", item.entityType());
      node.put("entityId", item.entityId());
      node.put("reason", item.reason());
    }
    jdbcClient
        .sql(
            "UPDATE import_batch SET status = 'CANCELLED', cancelled_at = :at, cancelled_count ="
                + " :count, not_cancelled_items = CAST(:items AS jsonb), cancellable_until = NULL,"
                + " updated_at = :at, version = version + 1 WHERE id = :id")
        .param("at", Timestamp.from(cancelledAt))
        .param("count", cancelledCount)
        .param("items", array.toString())
        .param("id", batchId)
        .update();
  }

  public List<ImportNotCancelledItem> findNotCancelled(long batchId) {
    String json =
        jdbcClient
            .sql("SELECT not_cancelled_items::text FROM import_batch WHERE id = :id")
            .param("id", batchId)
            .query(String.class)
            .optional()
            .orElse(null);
    List<ImportNotCancelledItem> items = new ArrayList<>();
    if (json == null) {
      return items;
    }
    for (JsonNode node : MAPPER.readTree(json)) {
      items.add(
          new ImportNotCancelledItem(
              node.get("entityType").asString(),
              node.get("entityId").asLong(),
              node.get("reason").asString()));
    }
    return items;
  }

  public int countQuotaUsed(long officeId, Instant monthStart, Instant monthEnd) {
    Integer used =
        jdbcClient
            .sql(
                "SELECT count(*) FROM import_batch b WHERE b.office_id = :officeId"
                    + " AND COALESCE(b.executed_at, b.updated_at) >= :start"
                    + " AND COALESCE(b.executed_at, b.updated_at) < :end"
                    + " AND (b.status = 'EXECUTING' OR b.cancelled_count >= 1 OR EXISTS (SELECT 1"
                    + " FROM import_record_link l WHERE l.batch_id = b.id))"
                    + " AND NOT EXISTS (SELECT 1 FROM import_support_request r WHERE"
                    + " r.import_batch_id = b.id AND r.status NOT IN ('CANCELLED','REJECTED'))")
            .param("officeId", officeId)
            .param("start", Timestamp.from(monthStart))
            .param("end", Timestamp.from(monthEnd))
            .query(Integer.class)
            .single();
    return used == null ? 0 : used;
  }
}
