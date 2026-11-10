package com.sairo.be.global.error;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class ErrorResponseIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void 업무_예외는_문서_형식으로_응답하고_요청_제한은_Retry_After를_담는다() throws Exception {
    mockMvc
        .perform(get("/api/probe/rate-limited"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "30"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
        .andExpect(jsonPath("$.retryAfterSeconds").value(30))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  void 필드_검증_실패는_fieldErrors에_코드를_담는다() throws Exception {
    assertFieldError(
        mockMvc.perform(
            post("/api/public/probe/validated")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"<b>굵게</b>\"}")),
        "name",
        "HTML_NOT_ALLOWED");
  }

  @Test
  void 허용되지_않는_페이지_크기는_size_필드_오류다() throws Exception {
    assertFieldError(
        mockMvc.perform(get("/api/probe/page").param("size", "30")), "size", "NOT_ALLOWED");
  }

  @Test
  void 페이지_응답은_문서의_목록_형식이다() throws Exception {
    mockMvc
        .perform(get("/api/probe/page"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(25))
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void 인증_실패와_권한_실패는_같은_오류_형식을_쓴다() throws Exception {
    assertError(mockMvc.perform(get("/api/probe/authenticated")), ErrorCode.UNAUTHENTICATED);
    assertError(mockMvc.perform(get("/api/probe/unannotated")), ErrorCode.FORBIDDEN);
  }
}
