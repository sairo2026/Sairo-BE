package com.sairo.be.global.error;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

// Spring Security's AuthenticationEntryPoint/AccessDeniedHandler run outside
// DispatcherServlet, so they can't rely on @ExceptionHandler/ProblemDetail message
// conversion. This writes the same {code, message, traceId} shape by hand.
@Profile("!migrate")
@Component
public class ErrorResponseWriter {

  private final ObjectMapper objectMapper;

  public ErrorResponseWriter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
    response.setStatus(errorCode.getStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    Map<String, String> body =
        Map.of(
            "code", errorCode.name(),
            "message", errorCode.getMessage(),
            "traceId", UUID.randomUUID().toString());
    objectMapper.writeValue(response.getWriter(), body);
  }
}
