package com.sairo.be.support;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import org.springframework.test.web.servlet.ResultActions;

public final class ErrorResponseAssertions {

  private static final String UUID_PATTERN =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

  private ErrorResponseAssertions() {}

  public static ResultActions assertError(ResultActions result, ErrorCode errorCode)
      throws Exception {
    return result
        .andExpect(status().is(errorCode.getStatus().value()))
        .andExpect(jsonPath("$.code").value(errorCode.name()))
        .andExpect(jsonPath("$.message").value(errorCode.getMessage()))
        .andExpect(jsonPath("$.traceId").value(matchesPattern(UUID_PATTERN)));
  }

  public static ResultActions assertFieldError(
      ResultActions result, String field, String fieldErrorCode) throws Exception {
    return assertError(result, ErrorCode.INVALID_REQUEST)
        .andExpect(
            jsonPath("$.fieldErrors[?(@.field == '" + field + "')].code").value(fieldErrorCode));
  }
}
