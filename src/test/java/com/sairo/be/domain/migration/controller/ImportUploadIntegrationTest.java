package com.sairo.be.domain.migration.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static com.sairo.be.support.SpreadsheetFixtures.csv;
import static com.sairo.be.support.SpreadsheetFixtures.csvWithRows;
import static com.sairo.be.support.SpreadsheetFixtures.row;
import static com.sairo.be.support.SpreadsheetFixtures.sheets;
import static com.sairo.be.support.SpreadsheetFixtures.xls;
import static com.sairo.be.support.SpreadsheetFixtures.xlsx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.ImportIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.SpreadsheetFixtures;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@IntegrationTest
class ImportUploadIntegrationTest extends ImportIntegrationTestBase {

  @Test
  void CSV를_올리면_업로드_상태의_실행_단위와_시트_정보를_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);

    mockMvc
        .perform(
            uploadRequest(
                cookie, "고객목록.csv", customerCsv("1,홍길동,010-1111-2222,a@b.co,메모"), "CUSTOMER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UPLOADED"))
        .andExpect(jsonPath("$.fileName").value("고객목록.csv"))
        .andExpect(jsonPath("$.sheets.length()").value(1))
        .andExpect(jsonPath("$.sheets[0].name").value("Sheet1"))
        .andExpect(jsonPath("$.sheets[0].rowCount").value(1))
        .andExpect(jsonPath("$.sheets[0].columnCount").value(5))
        .andExpect(jsonPath("$.selectedSheetName").value("Sheet1"))
        .andExpect(jsonPath("$.officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.createdAt").value(matchesPattern(".*\\+09:00$")))
        .andExpect(jsonPath("$.updatedAt").exists());

    Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM import_batch");
    assertThat(row)
        .containsEntry("status", "UPLOADED")
        .containsEntry("target_type", "CUSTOMER")
        .containsEntry("source_system", "UNSPECIFIED")
        .containsEntry("uploaded_by_membership_id", owner.membership().getId());
    assertThat(objectStorage.find((String) row.get("file_object_key"))).isPresent();
  }

  @Test
  void 여러_시트의_XLSX는_시트_목록을_돌려주고_첫_시트를_고른다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    byte[] content =
        xlsx(
            sheets(
                "고객",
                List.of(row("고객번호", "성명"), row(1, "홍길동"), row(2, "김철수")),
                "메모",
                List.of(row("내용"), row("a"))));

    mockMvc
        .perform(uploadRequest(cookieOf(owner), "고객.xlsx", content, "CUSTOMER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sheets.length()").value(2))
        .andExpect(jsonPath("$.sheets[0].name").value("고객"))
        .andExpect(jsonPath("$.sheets[0].rowCount").value(2))
        .andExpect(jsonPath("$.sheets[1].name").value("메모"))
        .andExpect(jsonPath("$.selectedSheetName").value("고객"));
  }

  @Test
  void XLS도_업로드할_수_있다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    byte[] content = xls(sheets("Sheet1", List.of(row("매물번호", "매물명"), row(1, "빌딩"))));

    mockMvc
        .perform(uploadRequest(cookieOf(owner), "매물.xls", content, "PROPERTY"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sheets[0].columnCount").value(2));
  }

  @Test
  void 계약_유형도_업로드할_수_있다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);

    mockMvc
        .perform(uploadRequest(cookieOf(owner), "계약.csv", csv("계약번호,당사자", "1,홍길동"), "CONTRACT"))
        .andExpect(status().isOk());
    assertThat(jdbcTemplate.queryForObject("SELECT target_type FROM import_batch", String.class))
        .isEqualTo("CONTRACT");
  }

  @Test
  void 월_한도를_다_쓴_대표도_파일은_올릴_수_있다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    useUpMonthlyQuota(owner, 5);

    mockMvc
        .perform(uploadRequest(cookieOf(owner), "고객.csv", customerCsv("1,홍길동,,,"), "CUSTOMER"))
        .andExpect(status().isOk());
  }

  @Test
  void 암호화된_파일은_400_ENCRYPTED_FILE_REJECTED다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);

    assertError(
        mockMvc.perform(
            uploadRequest(cookie, "a.xlsx", SpreadsheetFixtures.encryptedOle2(), "CUSTOMER")),
        ErrorCode.ENCRYPTED_FILE_REJECTED);
    assertError(
        mockMvc.perform(
            uploadRequest(cookie, "a.xls", SpreadsheetFixtures.encryptedOle2(), "CUSTOMER")),
        ErrorCode.ENCRYPTED_FILE_REJECTED);
    assertThat(count("import_batch")).isZero();
  }

  @Test
  void 지원하지_않는_형식이나_확장자와_내용이_다른_파일은_400_UNSUPPORTED_FILE_TYPE이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    byte[] xlsxContent = xlsx(sheets("S", List.of(row("a"), row("b"))));

    assertError(
        mockMvc.perform(uploadRequest(cookie, "a.txt", csv("a,b"), "CUSTOMER")),
        ErrorCode.UNSUPPORTED_FILE_TYPE);
    assertError(
        mockMvc.perform(uploadRequest(cookie, "a.pdf", csv("a,b"), "CUSTOMER")),
        ErrorCode.UNSUPPORTED_FILE_TYPE);
    assertError(
        mockMvc.perform(uploadRequest(cookie, "a.xlsx", csv("a,b"), "CUSTOMER")),
        ErrorCode.UNSUPPORTED_FILE_TYPE);
    assertError(
        mockMvc.perform(uploadRequest(cookie, "a.csv", xlsxContent, "CUSTOMER")),
        ErrorCode.UNSUPPORTED_FILE_TYPE);
    assertError(
        mockMvc.perform(uploadRequest(cookie, "a.xls", xlsxContent, "CUSTOMER")),
        ErrorCode.UNSUPPORTED_FILE_TYPE);
    assertThat(count("import_batch")).isZero();
  }

  @Test
  void 시트당_1만_행이나_100열을_넘으면_400_IMPORT_LIMIT_EXCEEDED다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);

    assertError(
        mockMvc.perform(uploadRequest(cookie, "a.csv", csvWithRows(10_001, 2), "CUSTOMER")),
        ErrorCode.IMPORT_LIMIT_EXCEEDED);
    assertError(
        mockMvc.perform(uploadRequest(cookie, "a.csv", csvWithRows(2, 101), "CUSTOMER")),
        ErrorCode.IMPORT_LIMIT_EXCEEDED);
    mockMvc
        .perform(uploadRequest(cookie, "a.csv", csvWithRows(10_000, 100), "CUSTOMER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sheets[0].rowCount").value(10_000))
        .andExpect(jsonPath("$.sheets[0].columnCount").value(100));
  }

  @Test
  void 파일이_20MB를_넘으면_413_FILE_TOO_LARGE다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    byte[] tooLarge = new byte[20 * 1024 * 1024 + 1];

    assertError(
        mockMvc.perform(uploadRequest(cookieOf(owner), "a.csv", tooLarge, "CUSTOMER")),
        ErrorCode.FILE_TOO_LARGE);
    assertThat(count("import_batch")).isZero();
  }

  @Test
  void 파일이나_유형이_없거나_잘못되면_400_INVALID_REQUEST다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);

    assertFieldError(
        mockMvc.perform(uploadRequest(cookie, "a.csv", null, "CUSTOMER")), "file", "REQUIRED");
    assertFieldError(
        mockMvc.perform(uploadRequest(cookie, "a.csv", csv("a"), null)), "targetType", "REQUIRED");
    assertFieldError(
        mockMvc.perform(uploadRequest(cookie, "a.csv", csv("a"), "ACCOUNT")),
        "targetType",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(uploadRequest(cookie, "a.csv", csv("", ""), "CUSTOMER")),
        "file",
        "INVALID_VALUE");
  }

  @Test
  void 대표가_아니면_403이고_세션이_없으면_401이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var agent = staffIn(owner.office(), OfficeMembershipRole.AGENT);
    var deputy = staffIn(owner.office(), OfficeMembershipRole.DEPUTY);

    assertError(
        mockMvc.perform(uploadRequest(cookieOf(agent), "a.csv", customerCsv("1,a,,,"), "CUSTOMER")),
        ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(
            uploadRequest(cookieOf(deputy), "a.csv", customerCsv("1,a,,,"), "CUSTOMER")),
        ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(uploadRequest(null, "a.csv", customerCsv("1,a,,,"), "CUSTOMER")),
        ErrorCode.UNAUTHENTICATED);
    assertThat(count("import_batch")).isZero();
  }
}
