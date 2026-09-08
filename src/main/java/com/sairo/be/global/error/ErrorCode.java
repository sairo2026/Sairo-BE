package com.sairo.be.global.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
  INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
  UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
  FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
  RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
  PUBLIC_LINK_NOT_FOUND(HttpStatus.NOT_FOUND, "유효하지 않거나 만료된 링크입니다."),
  INVALID_TRANSITION(HttpStatus.CONFLICT, "현재 상태에서는 처리할 수 없습니다."),
  RESPONSE_NOT_RESTARTABLE(HttpStatus.CONFLICT, "재시작할 수 없는 고객 응답입니다."),
  CANDIDATE_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT, "선택할 수 없는 후보 시간입니다.");

  private final HttpStatus status;
  private final String message;

  ErrorCode(HttpStatus status, String message) {
    this.status = status;
    this.message = message;
  }
}
