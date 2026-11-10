package com.sairo.be.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.JsonNode;

public abstract class ImportIntegrationTestBase extends CustomerIntegrationTestBase {

  protected static final String CUSTOMER_HEADER = "고객번호,성명,연락처,이메일,메모";
  protected static final String PROPERTY_HEADER = "매물번호,매물명,주소,상세주소,전용면적(평),관리비(만원),입주가능일,점유상태";

  protected static final String CUSTOMER_MAPPING =
      "{\"sourceSystem\":\"양셀\",\"mappings\":["
          + "{\"sourceColumn\":\"고객번호\",\"targetField\":\"customerSourceId\"},"
          + "{\"sourceColumn\":\"성명\",\"targetField\":\"customerName\"},"
          + "{\"sourceColumn\":\"연락처\",\"targetField\":\"customerPhone\"},"
          + "{\"sourceColumn\":\"이메일\",\"targetField\":\"customerEmail\"},"
          + "{\"sourceColumn\":\"메모\",\"targetField\":\"customerMemo\"}]}";

  protected static final String PROPERTY_MAPPING =
      "{\"sourceSystem\":\"양셀\",\"mappings\":["
          + "{\"sourceColumn\":\"매물번호\",\"targetField\":\"propertySourceId\"},"
          + "{\"sourceColumn\":\"매물명\",\"targetField\":\"propertyName\"},"
          + "{\"sourceColumn\":\"주소\",\"targetField\":\"propertyAddress\"},"
          + "{\"sourceColumn\":\"상세주소\",\"targetField\":\"propertyAddressDetail\"},"
          + "{\"sourceColumn\":\"전용면적(평)\",\"targetField\":\"exclusiveAreaSqm\"},"
          + "{\"sourceColumn\":\"관리비(만원)\",\"targetField\":\"maintenanceFeeAmount\"},"
          + "{\"sourceColumn\":\"입주가능일\",\"targetField\":\"moveInDate\"},"
          + "{\"sourceColumn\":\"점유상태\",\"targetField\":\"occupancyStatus\"}]}";

  protected static byte[] customerCsv(String... rows) {
    return csvWith(CUSTOMER_HEADER, rows);
  }

  protected static byte[] propertyCsv(String... rows) {
    return csvWith(PROPERTY_HEADER, rows);
  }

  protected static byte[] csvWith(String header, String... rows) {
    List<String> lines = new ArrayList<>();
    lines.add(header);
    lines.addAll(List.of(rows));
    return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
  }

  protected static RequestBuilder uploadRequest(
      Cookie cookie, String fileName, byte[] content, String targetType) {
    var builder = multipart("/api/import-batches");
    if (content != null) {
      builder.file(new MockMultipartFile("file", fileName, "application/octet-stream", content));
    }
    if (targetType != null) {
      builder.param("targetType", targetType);
    }
    if (cookie != null) {
      builder.cookie(cookie);
    }
    return builder
        .cookie(new Cookie("XSRF-TOKEN", AuthFlowSupport.CSRF_VALUE))
        .header("X-XSRF-TOKEN", AuthFlowSupport.CSRF_VALUE);
  }

  protected long uploadBatch(Cookie cookie, String fileName, byte[] content, String targetType)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(uploadRequest(cookie, fileName, content, targetType))
            .andExpect(status().isOk())
            .andReturn();
    return AuthFlowSupport.json(result).get("batchId").longValue();
  }

  protected long uploadCustomers(Cookie cookie, String... rows) throws Exception {
    return uploadBatch(cookie, "고객.csv", customerCsv(rows), "CUSTOMER");
  }

  protected long uploadProperties(Cookie cookie, String... rows) throws Exception {
    return uploadBatch(cookie, "매물.csv", propertyCsv(rows), "PROPERTY");
  }

  protected JsonNode confirmMapping(Cookie cookie, long batchId, String body) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                jsonPutAs(
                    "/api/import-batches/" + batchId + "/mapping",
                    body,
                    cookie,
                    batchVersion(batchId)))
            .andExpect(status().isOk())
            .andReturn();
    return AuthFlowSupport.json(result);
  }

  protected long readyCustomerBatch(Cookie cookie, String... rows) throws Exception {
    long batchId = uploadCustomers(cookie, rows);
    confirmMapping(cookie, batchId, CUSTOMER_MAPPING);
    return batchId;
  }

  protected long readyPropertyBatch(Cookie cookie, String... rows) throws Exception {
    long batchId = uploadProperties(cookie, rows);
    confirmMapping(cookie, batchId, PROPERTY_MAPPING);
    return batchId;
  }

  protected JsonNode executeBatch(Cookie cookie, long batchId) throws Exception {
    MvcResult result =
        mockMvc
            .perform(jsonPostAs("/api/import-batches/" + batchId + "/execute", "", cookie))
            .andExpect(status().isOk())
            .andReturn();
    return AuthFlowSupport.json(result);
  }

  protected JsonNode detail(Cookie cookie, long batchId) throws Exception {
    MvcResult result =
        mockMvc
            .perform(authedGet("/api/import-batches/" + batchId, cookie))
            .andExpect(status().isOk())
            .andReturn();
    return AuthFlowSupport.json(result);
  }

  protected long batchVersion(long batchId) {
    Long version =
        jdbcTemplate.queryForObject(
            "SELECT version FROM import_batch WHERE id = ?", Long.class, batchId);
    return version == null ? -1 : version;
  }

  protected String batchStatus(long batchId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM import_batch WHERE id = ?", String.class, batchId);
  }

  protected long count(String table) {
    Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    return count == null ? -1 : count;
  }

  protected void forceStatus(long batchId, String status) {
    jdbcTemplate.update("UPDATE import_batch SET status = ? WHERE id = ?", status, batchId);
  }

  protected long insertSupportRequest(long officeId, long batchId, String status) {
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO import_support_request (office_id, source_system, status, import_batch_id)"
                + " VALUES (?, '양셀', ?, ?) RETURNING id",
            Long.class,
            officeId,
            status,
            batchId);
    return id == null ? -1 : id;
  }

  protected long insertExecutedBatch(TestData.Staff owner, String sourceId) {
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO import_batch (office_id, uploaded_by_membership_id, source_system,"
                + " target_type, file_object_key, file_name, row_count, succeeded_count,"
                + " failed_count, status, executed_at, cancellable_until) VALUES (?, ?, '이전', 'CUSTOMER',"
                + " 'k', 'f.csv', 1, 1, 0, 'COMPLETED', now(), now() + interval '24 hours')"
                + " RETURNING id",
            Long.class,
            owner.office().getId(),
            owner.membership().getId());
    long batchId = id == null ? -1 : id;
    jdbcTemplate.update(
        "INSERT INTO import_record_link (batch_id, office_id, source_system, source_type,"
            + " source_id, target_entity_type, target_entity_id) VALUES (?, ?, '이전', 'CUSTOMER',"
            + " ?, 'CUSTOMER', 999999)",
        batchId,
        owner.office().getId(),
        sourceId);
    return batchId;
  }

  protected void useUpMonthlyQuota(TestData.Staff owner, int count) {
    for (int i = 0; i < count; i++) {
      insertExecutedBatch(owner, "quota-" + i);
    }
  }
}
