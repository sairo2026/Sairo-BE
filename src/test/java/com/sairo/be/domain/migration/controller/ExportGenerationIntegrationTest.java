package com.sairo.be.domain.migration.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.migration.service.ExportGenerationService;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.stub.InMemoryObjectStorage;
import com.sairo.be.support.ExportIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class ExportGenerationIntegrationTest extends ExportIntegrationTestBase {

  @Autowired private ExportGenerationService generationService;
  @MockitoSpyBean private InMemoryObjectStorage spiedStorage;

  private static String text(Map<String, byte[]> entries, String name) {
    return new String(entries.get(name), StandardCharsets.UTF_8);
  }

  private long insertPending(long officeId, long membershipId, String scope, String tablesJson) {
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO export_job (office_id, created_by_membership_id, scope, table_codes,"
                + " status, file_expires_at, created_at, updated_at) VALUES (?, ?, ?,"
                + " ?::jsonb, 'PENDING', now() + interval '24 hours', now(), now())"
                + " RETURNING id",
            Long.class,
            officeId,
            membershipId,
            scope,
            tablesJson);
    return id == null ? -1 : id;
  }

  @Test
  void 전체_내보내기_ZIP은_모든_표와_필드_사전과_관계_ID와_매니페스트를_담는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long propertyId = createProperty(cookie, "내보낼 매물", categoryIdOf("APARTMENT"));
    insertCustomer(owner.office().getId(), owner.membership().getId(), "홍길동");
    grantStepUp(cookie);

    long exportId = createExport(cookie, "{\"scope\":\"FULL\"}");

    assertThat(exportStatus(exportId)).isEqualTo("COMPLETED");
    Map<String, byte[]> entries = unzipExport(exportId);
    assertThat(entries.keySet())
        .contains(
            "data/property.csv",
            "data/customer.csv",
            "data/contract.csv",
            "data/contract_fee.csv",
            "data/contract_payment_schedule.csv",
            "data/task.csv",
            "data/visit_coordination.csv",
            "field_dictionary.csv",
            "relations.csv",
            "manifest.json");
    assertThat(entries.get("data/property.csv")[0]).isEqualTo((byte) 0xEF);
    assertThat(text(entries, "data/property.csv")).contains("내보낼 매물").contains("id");
    assertThat(text(entries, "data/customer.csv")).contains("홍길동");
    assertThat(text(entries, "field_dictionary.csv")).contains("property").contains("office_id");
    assertThat(text(entries, "relations.csv")).contains("property_photo,property_id,property,id");
    JsonNode manifest = JsonMapper.builder().build().readTree(entries.get("manifest.json"));
    assertThat(manifest.get("exportId").longValue()).isEqualTo(exportId);
    assertThat(manifest.get("scope").asString()).isEqualTo("FULL");
    assertThat(manifest.get("tables")).hasSize(7);
    assertThat(manifest.get("rowCounts").get("data/property.csv").longValue()).isEqualTo(1);
    assertThat(propertyId).isPositive();
  }

  @Test
  void 표_내보내기는_고른_표의_CSV만_담고_다른_사무소_자료는_넣지_않는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    insertCustomer(owner.office().getId(), owner.membership().getId(), "우리고객");
    insertCustomer(other.office().getId(), other.membership().getId(), "남의고객");
    createProperty(cookieOf(owner), "우리매물", categoryIdOf("APARTMENT"));

    long exportId = createExport(cookieOf(owner), tablesBody("CUSTOMER"));

    Map<String, byte[]> entries = unzipExport(exportId);
    assertThat(entries.keySet())
        .contains("data/customer.csv", "data/customer_consultation.csv")
        .doesNotContain("data/property.csv", "manifest.json", "field_dictionary.csv");
    assertThat(text(entries, "data/customer.csv")).contains("우리고객").doesNotContain("남의고객");
  }

  @Test
  void 값이_없는_표도_머리글_줄은_남기고_수식으로_읽힐_문자열은_무력화한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    insertCustomer(owner.office().getId(), owner.membership().getId(), "=SUM(A1)");

    long exportId = createExport(cookieOf(owner), tablesBody("CUSTOMER", "TASK"));

    Map<String, byte[]> entries = unzipExport(exportId);
    assertThat(text(entries, "data/task.csv")).startsWith("﻿id,").doesNotContain("\r\n1,");
    assertThat(text(entries, "data/customer.csv")).contains("'=SUM(A1)");
  }

  @Test
  void 전체_내보내기는_첨부_파일을_ZIP에_넣고_없는_파일은_매니페스트에_표시한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long propertyId = createProperty(cookie, "사진 매물", categoryIdOf("APARTMENT"));
    objectStorage.put("photos/a.jpg", new byte[] {1, 2, 3}, "image/jpeg");
    Long present =
        jdbcTemplate.queryForObject(
            "INSERT INTO property_photo (property_id, object_key, sort_order) VALUES (?, ?, 1)"
                + " RETURNING id",
            Long.class,
            propertyId,
            "photos/a.jpg");
    jdbcTemplate.update(
        "INSERT INTO property_photo (property_id, object_key, sort_order) VALUES (?, ?, 2)",
        propertyId,
        "photos/missing.jpg");
    grantStepUp(cookie);

    long exportId = createExport(cookie, "{\"scope\":\"FULL\"}");

    Map<String, byte[]> entries = unzipExport(exportId);
    assertThat(entries.get("attachments/property_photo/" + present + ".jpg"))
        .containsExactly(1, 2, 3);
    JsonNode manifest = JsonMapper.builder().build().readTree(entries.get("manifest.json"));
    assertThat(manifest.get("attachmentSummary").get("included").longValue()).isEqualTo(1);
    assertThat(manifest.get("attachmentSummary").get("missing").longValue()).isEqualTo(1);
    assertThat(text(entries, "data/property_photo.csv")).doesNotContain("photos/a.jpg");
  }

  @Test
  void 반환권한_내보내기가_남긴_진행_중_행도_이_생성기가_파일을_만든다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    insertCustomer(owner.office().getId(), owner.membership().getId(), "반환고객");
    long exportId = insertPending(owner.office().getId(), owner.membership().getId(), "FULL", null);

    int handled = generationService.generatePending();

    assertThat(handled).isEqualTo(1);
    assertThat(exportStatus(exportId)).isEqualTo("COMPLETED");
    assertThat(exportKey(exportId)).startsWith("exports/");
    assertThat(text(unzipExport(exportId), "data/customer.csv")).contains("반환고객");
    assertThat(generationService.generatePending()).isZero();
  }

  private long insertRetainedDocument(
      long contractId,
      long revisionId,
      long membershipId,
      String type,
      int years,
      String retentionEnd,
      String objectKey) {
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO contract_document (contract_id, contract_revision_id, document_type,"
                + " version_no, written_date, delivered_date, retention_end_date, retention_years,"
                + " object_key, uploaded_by_membership_id) VALUES (?, ?, ?, 1, current_date,"
                + " current_date, ?::date, ?, ?, ?) RETURNING id",
            Long.class,
            contractId,
            revisionId,
            type,
            retentionEnd,
            years,
            objectKey,
            membershipId);
    return id == null ? -1 : id;
  }

  @Test
  void 보관권한_내보내기는_법정_보존_계약_서류와_거래_기록만_담는다() throws Exception {
    jdbcTemplate.update(
        "INSERT INTO legal_retention_rule (rule_code, data_group_label, retention_days,"
            + " anchor_event, basis_note, status) VALUES ('OFFICE_CONTRACT_DOCUMENT', '거래계약서',"
            + " 1825, 'RECORDED_AT', '시험', 'ASSUMPTION'), ('OFFICE_EXPLANATION_DOCUMENT',"
            + " '확인설명서', 1095, 'RECORDED_AT', '시험', 'ASSUMPTION') ON CONFLICT (rule_code) DO"
            + " NOTHING");
    var owner = newStaff(OfficeMembershipRole.OWNER);
    long officeId = owner.office().getId();
    long membershipId = owner.membership().getId();
    long propertyId = createProperty(cookieOf(owner), "보관 제외 매물", categoryIdOf("APARTMENT"));
    insertCustomer(officeId, membershipId, "보관제외고객");
    Long contractId =
        jdbcTemplate.queryForObject(
            "INSERT INTO contract (office_id, property_id, deal_type, managing_membership_id)"
                + " VALUES (?, ?, 'SALE', ?) RETURNING id",
            Long.class,
            officeId,
            propertyId,
            membershipId);
    Long revisionId =
        jdbcTemplate.queryForObject(
            "INSERT INTO contract_revision (contract_id, revision_no, revision_type,"
                + " current_snapshot, effective_at, recorded_by_membership_id) VALUES (?, 1,"
                + " 'SIGNED', '{}'::jsonb, now(), ?) RETURNING id",
            Long.class,
            contractId,
            membershipId);
    objectStorage.put("contracts/kept.pdf", new byte[] {7, 8, 9}, "application/pdf");
    objectStorage.put("contracts/past.pdf", new byte[] {1}, "application/pdf");
    long kept =
        insertRetainedDocument(
            contractId,
            revisionId,
            membershipId,
            "SALES_CONTRACT",
            5,
            "2031-01-01",
            "contracts/kept.pdf");
    long past =
        insertRetainedDocument(
            contractId,
            revisionId,
            membershipId,
            "DISCLOSURE_STATEMENT",
            3,
            "2020-01-01",
            "contracts/past.pdf");
    long grantId =
        jdbcTemplate.queryForObject(
            "INSERT INTO archive_grant (office_id, granted_to_user_id, contract_reference) VALUES"
                + " (?, ?, 'ARCHIVE-1') RETURNING id",
            Long.class,
            officeId,
            owner.user().getId());
    Long exportId =
        jdbcTemplate.queryForObject(
            "INSERT INTO export_job (office_id, created_by_membership_id, scope, archive_grant_id,"
                + " status, file_expires_at, created_at, updated_at) VALUES (?, ?, 'ARCHIVE', ?,"
                + " 'PENDING', now() + interval '24 hours', now(), now()) RETURNING id",
            Long.class,
            officeId,
            membershipId,
            grantId);

    generationService.generate(exportId);

    assertThat(exportStatus(exportId)).isEqualTo("COMPLETED");
    Map<String, byte[]> entries = unzipExport(exportId);
    assertThat(entries.keySet())
        .contains(
            "data/contract.csv",
            "data/contract_document.csv",
            "data/contract_fee.csv",
            "data/contract_payment_schedule.csv",
            "attachments/contract_document/" + kept + ".pdf",
            "manifest.json")
        .doesNotContain(
            "data/property.csv",
            "data/customer.csv",
            "data/task.csv",
            "attachments/contract_document/" + past + ".pdf");
    assertThat(entries.get("attachments/contract_document/" + kept + ".pdf"))
        .containsExactly(7, 8, 9);
    assertThat(text(entries, "data/contract_document.csv"))
        .contains("SALES_CONTRACT")
        .doesNotContain("DISCLOSURE_STATEMENT");
    JsonNode manifest = JsonMapper.builder().build().readTree(entries.get("manifest.json"));
    assertThat(manifest.get("scope").asString()).isEqualTo("ARCHIVE");
    assertThat(manifest.get("archiveGrantId").longValue()).isEqualTo(grantId);
    assertThat(manifest.get("attachmentSummary").get("included").longValue()).isEqualTo(1);
  }

  @Test
  void 이미_처리한_내보내기는_다시_생성하지_않는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    long exportId = createExport(cookieOf(owner), tablesBody("TASK"));
    String keyBefore = exportKey(exportId);

    generationService.generate(exportId);

    assertThat(exportKey(exportId)).isEqualTo(keyBefore);
  }

  @Test
  void 파일_생성이_실패하면_FAILED와_사유를_남기고_고객_응답에_내부_사정을_넣지_않는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    doThrow(new IllegalStateException("storage down"))
        .when(spiedStorage)
        .put(anyString(), any(), anyString());

    long exportId = createExport(cookie, tablesBody("TASK"));

    assertThat(exportStatus(exportId)).isEqualTo("FAILED");
    assertThat(exportKey(exportId)).isNull();
    getExport(cookie, exportId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.failureReason").value(ExportGenerationService.FAILURE_REASON))
        .andExpect(jsonPath("$.downloadUrl").doesNotExist());
  }

  @Test
  void 만료된_파일은_저장소에서_지우고_이후_조회는_410이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long exportId = createExport(cookie, tablesBody("TASK"));
    String key = exportKey(exportId);
    expireExport(exportId);

    int purged = generationService.purgeExpired();

    assertThat(purged).isEqualTo(1);
    assertThat(objectStorage.find(key)).isEmpty();
    assertThat(exportKey(exportId)).isNull();
    assertError(getExport(cookie, exportId), ErrorCode.EXPORT_EXPIRED);
    assertThat(generationService.purgeExpired()).isZero();
  }
}
