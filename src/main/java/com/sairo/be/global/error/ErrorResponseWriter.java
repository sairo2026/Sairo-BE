package com.sairo.be.global.error;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Profile("!migrate")
@Component
public class ErrorResponseWriter {

  private final ObjectMapper objectMapper;

  public ErrorResponseWriter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
    write(response, new BusinessException(errorCode));
  }

  public void write(HttpServletResponse response, BusinessException exception) throws IOException {
    ErrorResponse body = ErrorResponse.of(exception, TraceIds.currentOrNew());
    response.setStatus(exception.getErrorCode().getStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
    Object retryAfter = body.details().get("retryAfterSeconds");
    if (retryAfter != null) {
      response.setHeader("Retry-After", String.valueOf(retryAfter));
    }
    objectMapper.writeValue(response.getWriter(), body);
  }
}
