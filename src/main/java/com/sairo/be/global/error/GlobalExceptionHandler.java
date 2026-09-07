package com.sairo.be.global.error;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ProblemDetail> handleBusinessException(BusinessException ex) {
    return toResponse(ex.getErrorCode(), ex.getMessage());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
    String traceId = UUID.randomUUID().toString();
    log.error("예상하지 못한 오류. traceId={}", traceId, ex);
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    problem.setProperty("code", "INTERNAL_ERROR");
    problem.setProperty("message", "일시적인 오류가 발생했습니다.");
    problem.setProperty("traceId", traceId);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail problem =
        buildBody(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.getMessage());
    problem.setProperty(
        "fieldErrors",
        ex.getBindingResult().getFieldErrors().stream().map(this::toFieldError).toList());
    return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus()).body(problem);
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
        .body(buildBody(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.getMessage()));
  }

  private FieldErrorItem toFieldError(FieldError fieldError) {
    return new FieldErrorItem(fieldError.getField(), fieldError.getDefaultMessage());
  }

  private ResponseEntity<ProblemDetail> toResponse(ErrorCode errorCode, String message) {
    return ResponseEntity.status(errorCode.getStatus()).body(buildBody(errorCode, message));
  }

  private ProblemDetail buildBody(ErrorCode errorCode, String message) {
    ProblemDetail problem = ProblemDetail.forStatus(errorCode.getStatus());
    problem.setProperty("code", errorCode.name());
    problem.setProperty("message", message);
    problem.setProperty("traceId", UUID.randomUUID().toString());
    return problem;
  }

  private record FieldErrorItem(String field, String message) {}
}
