package com.sairo.be.domain.property.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.TestcontainersConfiguration;
import com.sairo.be.global.security.StaffAuthentication;
import com.sairo.be.global.security.StaffPrincipal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PropertyControllerTest {

  private static final Long OFFICE_ID = 9301L;
  private static final Long OTHER_OFFICE_ID = 9302L;

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  private StaffAuthentication staffAuthentication;
  private StaffAuthentication otherOfficeAuthentication;

  @BeforeEach
  void seed() {
    jdbcTemplate.update("DELETE FROM property");
    jdbcTemplate.update("DELETE FROM office_membership");
    jdbcTemplate.update("DELETE FROM app_user");
    jdbcTemplate.update("DELETE FROM office");
    insertOffice(OFFICE_ID, "11223344556");
    insertOffice(OTHER_OFFICE_ID, "99887766554");

    staffAuthentication =
        new StaffAuthentication(new StaffPrincipal(9401L, 9501L, OFFICE_ID, "박직원"));
    otherOfficeAuthentication =
        new StaffAuthentication(new StaffPrincipal(9402L, 9502L, OTHER_OFFICE_ID, "다른사무소직원"));
  }

  private void insertOffice(Long officeId, String licenseNumber) {
    jdbcTemplate.update(
        "INSERT INTO office (id, name, representative_name, business_registration_number,"
            + " real_estate_license_number, phone, address, created_at)"
            + " VALUES (?, '사이로 데모 사무소', '김대표', ?, ?, '02-1234-5678', '서울시 강남구', now())",
        officeId,
        officeId + 1000000,
        licenseNumber);
  }

  @Test
  void 매물_등록_후_목록에서_조회된다() throws Exception {
    mockMvc
        .perform(
            post("/api/properties")
                .with(authentication(staffAuthentication))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"address":"서울시 강남구 테헤란로 1","addressDetail":"101호","propertyName":"테스트빌","dealType":"JEONSE"}
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.address").value("서울시 강남구 테헤란로 1"))
        .andExpect(jsonPath("$.dealType").value("JEONSE"));

    mockMvc
        .perform(get("/api/properties").with(authentication(staffAuthentication)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.properties", hasSize(1)))
        .andExpect(jsonPath("$.properties[0].propertyName").value("테스트빌"));
  }

  @Test
  void 매물명_없이_등록해도_성공한다() throws Exception {
    mockMvc
        .perform(
            post("/api/properties")
                .with(authentication(staffAuthentication))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"address":"서울시 서초구 반포대로 10","dealType":"SALE"}
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.propertyName").doesNotExist());
  }

  @Test
  void 주소_없이_등록하면_400을_반환한다() throws Exception {
    mockMvc
        .perform(
            post("/api/properties")
                .with(authentication(staffAuthentication))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"dealType":"SALE"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void 잘못된_거래유형이면_400을_반환한다() throws Exception {
    mockMvc
        .perform(
            post("/api/properties")
                .with(authentication(staffAuthentication))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"address":"서울시 마포구 월드컵로 1","dealType":"NOT_A_TYPE"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void 같은_주소가_있어도_등록은_허용된다() throws Exception {
    registerProperty(staffAuthentication, "서울시 송파구 올림픽로 1", "SALE").andExpect(status().isCreated());

    mockMvc
        .perform(
            post("/api/properties")
                .with(authentication(staffAuthentication))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"address":"서울시 송파구 올림픽로 1","dealType":"JEONSE"}
                    """))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            get("/api/properties/duplicate-check")
                .with(authentication(staffAuthentication))
                .param("address", "서울시 송파구 올림픽로 1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.duplicateProperties", hasSize(2)));
  }

  @Test
  void 매물_조회와_수정이_정상_동작한다() throws Exception {
    Long propertyId = createAndExtractId("서울시 종로구 세종대로 1", "MONTHLY");

    mockMvc
        .perform(
            get("/api/properties/{propertyId}", propertyId)
                .with(authentication(staffAuthentication)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.address").value("서울시 종로구 세종대로 1"));

    mockMvc
        .perform(
            patch("/api/properties/{propertyId}", propertyId)
                .with(authentication(staffAuthentication))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"address":"서울시 종로구 세종대로 2","addressDetail":"5층","propertyName":"수정된 매물명"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.address").value("서울시 종로구 세종대로 2"))
        .andExpect(jsonPath("$.addressDetail").value("5층"))
        .andExpect(jsonPath("$.propertyName").value("수정된 매물명"))
        .andExpect(jsonPath("$.dealType").value("MONTHLY"));
  }

  @Test
  void 존재하지_않는_매물_조회는_404를_반환한다() throws Exception {
    mockMvc
        .perform(
            get("/api/properties/{propertyId}", 999999L).with(authentication(staffAuthentication)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  void 다른_사무소_매물_조회는_404를_반환한다() throws Exception {
    Long propertyId = createAndExtractId("서울시 강동구 천호대로 1", "SALE");

    mockMvc
        .perform(
            get("/api/properties/{propertyId}", propertyId)
                .with(authentication(otherOfficeAuthentication)))
        .andExpect(status().isNotFound());
  }

  @Test
  void 인증되지_않은_요청은_401을_반환한다() throws Exception {
    mockMvc.perform(get("/api/properties")).andExpect(status().isUnauthorized());
  }

  private ResultActions registerProperty(
      StaffAuthentication authentication, String address, String dealType) throws Exception {
    return mockMvc.perform(
        post("/api/properties")
            .with(authentication(authentication))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"address\":\"" + address + "\",\"dealType\":\"" + dealType + "\"}"));
  }

  private Long createAndExtractId(String address, String dealType) throws Exception {
    String body =
        registerProperty(staffAuthentication, address, dealType)
            .andReturn()
            .getResponse()
            .getContentAsString();
    Matcher matcher = Pattern.compile("\"propertyId\":(\\d+)").matcher(body);
    if (!matcher.find()) {
      throw new IllegalStateException("propertyId not found in response: " + body);
    }
    return Long.valueOf(matcher.group(1));
  }
}
