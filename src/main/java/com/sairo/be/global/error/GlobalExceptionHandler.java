package com.sairo.be.global.error;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException exception) {
    ResponseEntity.BodyBuilder builder =
        ResponseEntity.status(exception.getErrorCode().getStatus());
    Object retryAfter = exception.getDetails().get("retryAfterSeconds");
    if (retryAfter != null) {
      builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
    }
    return builder.body(ErrorResponse.of(exception, TraceIds.currentOrNew()));
  }

  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException exception) {
    return respond(ErrorCode.UNAUTHENTICATED);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException exception) {
    return respond(ErrorCode.FORBIDDEN);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ErrorResponse> handleConstraintViolation(
      ConstraintViolationException exception) {
    List<FieldErrorItem> items = new ArrayList<>();
    for (ConstraintViolation<?> violation : exception.getConstraintViolations()) {
      items.add(
          new FieldErrorItem(
              violation.getPropertyPath().toString(),
              FieldErrorCode.INVALID_VALUE,
              violation.getMessage()));
    }
    return respond(BusinessException.withFieldErrors(ErrorCode.INVALID_REQUEST, items));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
    String traceId = TraceIds.currentOrNew();
    log.error("예상하지 못한 오류. traceId={}", traceId, exception);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            ErrorResponse.of(
                ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMessage(), traceId));
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception exception,
      Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    if (statusCode.value() == HttpStatus.CONTENT_TOO_LARGE.value()
        && request.getDescription(false).startsWith("uri=/api/support-inquiries/")) {
      return ResponseEntity.status(ErrorCode.ATTACHMENT_TOO_LARGE.getStatus())
          .body(
              ErrorResponse.of(
                  ErrorCode.ATTACHMENT_TOO_LARGE,
                  ErrorCode.ATTACHMENT_TOO_LARGE.getMessage(),
                  TraceIds.currentOrNew()));
    }
    if (statusCode.value() == HttpStatus.CONTENT_TOO_LARGE.value()
        && request.getDescription(false).startsWith("uri=/api/contracts/")) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(
              ErrorResponse.of(
                  BusinessException.withFieldErrors(
                      ErrorCode.INVALID_REQUEST,
                      List.of(
                          new FieldErrorItem(
                              "file", FieldErrorCode.OUT_OF_RANGE, "파일은 20MB까지 올릴 수 있습니다."))),
                  TraceIds.currentOrNew()));
    }
    ErrorCode errorCode = errorCodeFor(statusCode);
    ErrorResponse response =
        ErrorResponse.of(errorCode, errorCode.getMessage(), TraceIds.currentOrNew());
    return ResponseEntity.status(statusCode).headers(headers).body(response);
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<FieldErrorItem> items = new ArrayList<>();
    for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
      items.add(FieldErrorMapper.fromFieldError(fieldError));
    }
    return invalidRequest(items);
  }

  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<FieldErrorItem> items = new ArrayList<>();
    exception
        .getParameterValidationResults()
        .forEach(
            result -> {
              String parameter =
                  result.getMethodParameter().getParameterName() == null
                      ? "parameter"
                      : result.getMethodParameter().getParameterName();
              result
                  .getResolvableErrors()
                  .forEach(
                      error ->
                          items.add(
                              FieldErrorMapper.fromResolvable(
                                  parameter, error, result.getArgument())));
            });
    return invalidRequest(items);
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<FieldErrorItem> items = new ArrayList<>();
    extractFieldError(exception.getCause()).ifPresent(items::add);
    return invalidRequest(items);
  }

  @Override
  protected ResponseEntity<Object> handleMissingServletRequestParameter(
      MissingServletRequestParameterException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return invalidRequest(
        List.of(
            new FieldErrorItem(
                exception.getParameterName(), FieldErrorCode.REQUIRED, "필수 파라미터입니다.")));
  }

  @Override
  protected ResponseEntity<Object> handleTypeMismatch(
      org.springframework.beans.TypeMismatchException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    String field =
        exception instanceof MethodArgumentTypeMismatchException mismatch
            ? mismatch.getName()
            : exception.getPropertyName();
    return invalidRequest(
        List.of(
            new FieldErrorItem(
                field == null ? "parameter" : field,
                FieldErrorCode.TYPE_MISMATCH,
                "값의 형식이 올바르지 않습니다.")));
  }

  private ResponseEntity<Object> invalidRequest(List<FieldErrorItem> items) {
    BusinessException exception =
        BusinessException.withFieldErrors(ErrorCode.INVALID_REQUEST, items);
    return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
        .body(ErrorResponse.of(exception, TraceIds.currentOrNew()));
  }

  private ResponseEntity<ErrorResponse> respond(ErrorCode errorCode) {
    return respond(new BusinessException(errorCode));
  }

  private ResponseEntity<ErrorResponse> respond(BusinessException exception) {
    return ResponseEntity.status(exception.getErrorCode().getStatus())
        .body(ErrorResponse.of(exception, TraceIds.currentOrNew()));
  }

  private ErrorCode errorCodeFor(HttpStatusCode statusCode) {
    int value = statusCode.value();
    if (value == HttpStatus.NOT_FOUND.value()) {
      return ErrorCode.RESOURCE_NOT_FOUND;
    }
    if (value == HttpStatus.CONTENT_TOO_LARGE.value()) {
      return ErrorCode.FILE_TOO_LARGE;
    }
    if (statusCode.is5xxServerError()) {
      return ErrorCode.INTERNAL_ERROR;
    }
    return ErrorCode.INVALID_REQUEST;
  }

  private Optional<FieldErrorItem> extractFieldError(Throwable cause) {
    if (!(cause instanceof MismatchedInputException mismatchedInputException)) {
      return Optional.empty();
    }
    List<JacksonException.Reference> path = mismatchedInputException.getPath();
    if (path.isEmpty()) {
      return Optional.empty();
    }
    String field = path.get(path.size() - 1).getPropertyName();
    if (field == null) {
      return Optional.empty();
    }
    if (cause instanceof InvalidFormatException invalidFormatException) {
      return Optional.of(
          new FieldErrorItem(
              field,
              FieldErrorCode.NOT_ALLOWED,
              "허용되지 않는 값입니다: " + invalidFormatException.getValue()));
    }
    return Optional.of(
        new FieldErrorItem(field, FieldErrorCode.TYPE_MISMATCH, "요청 형식이 올바르지 않습니다."));
  }
}
