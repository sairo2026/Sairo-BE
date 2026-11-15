package com.sairo.be.domain.migration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.ImportIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ImportDuplicateIntegrationTest extends ImportIntegrationTestBase {

  @Test
  void 미리보기는_기존_고객과_같은_이름_전화번호_행과_파일_안_중복을_구분해_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long existing = createCustomer(cookie, "홍길동", "010-1234-5678", owner.membership().getId());
    long batchId =
        readyCustomerBatch(
            cookie,
            "C-1,홍길동,01012345678,,",
            "C-2,홍길동,01099998888,,",
            "C-3,홍길동,,,",
            "C-4,이영희,010-5555-0000,,",
            "C-5,이영희,01055550000,,",
            "C-6,,,,");

    mockMvc
        .perform(authedGet("/api/import-batches/" + batchId + "/preview", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].duplicateJudgement").value("DUPLICATE_EXISTING"))
        .andExpect(jsonPath("$.rows[0].matchedTargetId").value(existing))
        .andExpect(jsonPath("$.rows[1].duplicateJudgement").value("NEW"))
        .andExpect(jsonPath("$.rows[2].duplicateJudgement").value("NEW"))
        .andExpect(jsonPath("$.rows[3].duplicateJudgement").value("NEW"))
        .andExpect(jsonPath("$.rows[4].duplicateJudgement").value("DUPLICATE_IN_FILE"))
        .andExpect(jsonPath("$.rows[5].duplicateJudgement").value("NEW"))
        .andExpect(jsonPath("$.duplicateWarnings.length()").value(1))
        .andExpect(jsonPath("$.duplicateWarnings[0].sourceRowNumber").value(2))
        .andExpect(jsonPath("$.duplicateWarnings[0].reason").value("DUPLICATE_EXISTING"))
        .andExpect(jsonPath("$.duplicateWarnings[0].existingTargetId").value(existing))
        .andExpect(jsonPath("$.duplicateWarnings[0].existingArchived").value(false))
        .andExpect(jsonPath("$.summary.rowCount").value(6))
        .andExpect(jsonPath("$.summary.errorRowCount").value(1))
        .andExpect(jsonPath("$.summary.duplicateCount").value(2))
        .andExpect(jsonPath("$.summary.duplicateExistingCount").value(1))
        .andExpect(jsonPath("$.summary.duplicateInFileCount").value(1))
        .andExpect(jsonPath("$.summary.alreadyImportedCount").value(0))
        .andExpect(jsonPath("$.summary.newCount").value(3));
  }

  @Test
  void 실행하면_중복은_만들지_않고_성공과_분리해_세며_합계가_파일_행_수와_같다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    createCustomer(cookie, "홍길동", "010-1234-5678", owner.membership().getId());
    long first = readyCustomerBatch(cookie, "C-9,박지성,01077770000,,");
    executeBatch(cookie, first);
    long batchId =
        readyCustomerBatch(
            cookie,
            "C-1,홍길동,01012345678,,",
            "C-2,홍길동,01099998888,,",
            "C-4,이영희,010-5555-0000,,",
            "C-5,이영희,01055550000,,",
            "C-9,박지성 수정,01077770000,,",
            "C-6,,,,");

    executeBatch(cookie, batchId);

    JsonNode counts = detail(cookie, batchId).get("counts");
    assertThat(counts.get("total").asInt()).isEqualTo(6);
    assertThat(counts.get("succeeded").asInt()).isEqualTo(2);
    assertThat(counts.get("failed").asInt()).isEqualTo(1);
    assertThat(counts.get("skippedDuplicate").asInt()).isEqualTo(3);
    assertThat(counts.get("unprocessed").asInt()).isZero();
    assertThat(counts.get("skippedByReason").get("alreadyImported").asInt()).isEqualTo(1);
    assertThat(counts.get("skippedByReason").get("duplicateInFile").asInt()).isEqualTo(1);
    assertThat(counts.get("skippedByReason").get("duplicateExisting").asInt()).isEqualTo(1);
    assertThat(
            counts.get("succeeded").asInt()
                + counts.get("failed").asInt()
                + counts.get("skippedDuplicate").asInt()
                + counts.get("unprocessed").asInt())
        .isEqualTo(counts.get("total").asInt());
    assertThat(count("customer")).isEqualTo(1 + 1 + 2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM customer WHERE phone = '010-1234-5678'", Long.class))
        .isEqualTo(1);
    List<Map<String, Object>> skipped =
        jdbcTemplate.queryForList(
            "SELECT source_row_number, skip_reason, matched_entity_id FROM import_row_result"
                + " WHERE batch_id = ? AND result = 'SKIPPED' ORDER BY source_row_number",
            batchId);
    assertThat(skipped).hasSize(3);
    assertThat(skipped.get(0)).containsEntry("skip_reason", "DUPLICATE_EXISTING");
    assertThat(skipped.get(1)).containsEntry("skip_reason", "DUPLICATE_IN_FILE");
    assertThat(skipped.get(2)).containsEntry("skip_reason", "ALREADY_IMPORTED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT skipped_duplicate_count FROM import_batch WHERE id = ?",
                Integer.class,
                batchId))
        .isEqualTo(3);
    mockMvc
        .perform(authedGet("/api/import-batches", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].batchId").value(batchId))
        .andExpect(jsonPath("$.items[0].succeededCount").value(2))
        .andExpect(jsonPath("$.items[0].failedCount").value(1))
        .andExpect(jsonPath("$.items[0].skippedDuplicateCount").value(3));
  }

  @Test
  void 모든_줄이_중복이면_성공이_0건이어도_완료이고_취소_기한이_없다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    createCustomer(cookie, "홍길동", "010-1234-5678", owner.membership().getId());
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,01012345678,,");

    executeBatch(cookie, batchId);

    JsonNode detail = detail(cookie, batchId);
    assertThat(detail.get("status").asString()).isEqualTo("COMPLETED");
    assertThat(detail.get("cancellableUntil").isNull()).isTrue();
    assertThat(detail.get("counts").get("succeeded").asInt()).isZero();
    assertThat(detail.get("counts").get("skippedDuplicate").asInt()).isEqualTo(1);
    assertThat(count("customer")).isEqualTo(1);
    assertThat(count("import_record_link")).isZero();
  }

  @Test
  void 이름_공백과_전화번호_기호는_무시하고_보조_연락처도_비교한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long existing = createCustomer(cookie, "홍 길동", "02-123-4567", owner.membership().getId());
    jdbcTemplate.update(
        "UPDATE customer SET secondary_phone = '010-1234-5678' WHERE id = ?", existing);
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,01012345678,,", "C-2,홍길동,021234567,,");

    mockMvc
        .perform(authedGet("/api/import-batches/" + batchId + "/preview", cookie))
        .andExpect(jsonPath("$.rows[0].duplicateJudgement").value("DUPLICATE_EXISTING"))
        .andExpect(jsonPath("$.rows[0].matchedTargetId").value(existing))
        .andExpect(jsonPath("$.rows[1].duplicateJudgement").value("DUPLICATE_EXISTING"));
  }

  @Test
  void 보관된_기존_고객과도_중복으로_보고_물리_삭제된_고객과는_중복으로_보지_않는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long archived = createCustomer(cookie, "홍길동", "010-1111-1111", owner.membership().getId());
    jdbcTemplate.update("UPDATE customer SET deleted_at = now() WHERE id = ?", archived);
    long removed = createCustomer(cookie, "김철수", "010-2222-2222", owner.membership().getId());
    jdbcTemplate.update("DELETE FROM entity_lifecycle_event WHERE customer_id = ?", removed);
    jdbcTemplate.update("DELETE FROM customer WHERE id = ?", removed);
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,01011111111,,", "C-2,김철수,01022222222,,");

    mockMvc
        .perform(authedGet("/api/import-batches/" + batchId + "/preview", cookie))
        .andExpect(jsonPath("$.rows[0].duplicateJudgement").value("DUPLICATE_EXISTING"))
        .andExpect(jsonPath("$.rows[0].matchedTargetId").value(archived))
        .andExpect(jsonPath("$.duplicateWarnings[0].existingArchived").value(true))
        .andExpect(jsonPath("$.rows[1].duplicateJudgement").value("NEW"));

    executeBatch(cookie, batchId);

    assertThat(count("customer")).isEqualTo(2);
  }

  @Test
  void 다른_고객에_병합된_고객과_같은_행은_병합_대상_고객과_중복이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long primary = createCustomer(cookie, "홍길동", "010-1111-1111", owner.membership().getId());
    long merged = createCustomer(cookie, "홍길동", "010-3333-3333", owner.membership().getId());
    jdbcTemplate.update(
        "INSERT INTO customer_merge_log (primary_customer_id, merged_customer_id,"
            + " executed_by_membership_id) VALUES (?, ?, ?)",
        primary,
        merged,
        owner.membership().getId());
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,01033333333,,");

    mockMvc
        .perform(authedGet("/api/import-batches/" + batchId + "/preview", cookie))
        .andExpect(jsonPath("$.rows[0].duplicateJudgement").value("DUPLICATE_EXISTING"))
        .andExpect(jsonPath("$.rows[0].matchedTargetId").value(primary));
  }

  @Test
  void 다른_사무소의_같은_이름_전화번호는_중복이_아니다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    createCustomer(cookieOf(other), "홍길동", "010-1234-5678", other.membership().getId());
    Cookie cookie = cookieOf(owner);
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,01012345678,,");

    executeBatch(cookie, batchId);

    assertThat(detail(cookie, batchId).get("counts").get("succeeded").asInt()).isEqualTo(1);
    assertThat(count("customer")).isEqualTo(2);
  }

  @Test
  void 재처리해도_중복으로_제외한_줄은_다시_세지_않고_합계가_유지된다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    createCustomer(cookie, "홍길동", "010-1234-5678", owner.membership().getId());
    long batchId =
        readyCustomerBatch(cookie, "C-1,홍길동,01012345678,,", "C-2,김철수,,이메일아님,", "C-3,이영희,,,");
    executeBatch(cookie, batchId);
    String key =
        jdbcTemplate.queryForObject(
            "SELECT file_object_key FROM import_batch WHERE id = ?", String.class, batchId);
    objectStorage.put(
        key,
        customerCsv("C-1,홍길동,01012345678,,", "C-2,김철수,,fixed@example.com,", "C-3,이영희,,,"),
        "text/csv");

    mockMvc
        .perform(jsonPostAs("/api/import-batches/" + batchId + "/retry-failed", "", cookie))
        .andExpect(status().isOk());

    JsonNode counts = detail(cookie, batchId).get("counts");
    assertThat(counts.get("succeeded").asInt()).isEqualTo(2);
    assertThat(counts.get("failed").asInt()).isZero();
    assertThat(counts.get("skippedDuplicate").asInt()).isEqualTo(1);
    assertThat(counts.get("unprocessed").asInt()).isZero();
    assertThat(count("customer")).isEqualTo(3);
    assertThat(
            AuthFlowSupport.json(
                    mockMvc.perform(authedGet("/api/import-batches", cookie)).andReturn())
                .get("items")
                .get(0)
                .get("skippedDuplicateCount")
                .asInt())
        .isEqualTo(1);
  }
}
