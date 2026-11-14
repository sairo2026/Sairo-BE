package com.sairo.be.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ErrorCodeTest {

  @Test
  void 오류_코드_이름은_중복되지_않고_대문자_스네이크_형식이다() {
    Set<String> names = new HashSet<>();
    for (ErrorCode code : ErrorCode.values()) {
      assertThat(code.name()).matches("[A-Z][A-Z0-9_]+");
      assertThat(names.add(code.name())).isTrue();
    }
  }

  @Test
  void 모든_오류_코드는_오류_상태와_한글_메시지를_가진다() {
    for (ErrorCode code : ErrorCode.values()) {
      assertThat(code.getStatus().isError()).as(code.name()).isTrue();
      assertThat(code.getMessage()).as(code.name()).isNotBlank();
    }
  }

  @Test
  void 문서가_정한_오류_코드를_모두_정의한다() {
    assertThat(ErrorCode.values()).hasSize(147);
    assertThat(Arrays.stream(ErrorCode.values()).filter(c -> c != ErrorCode.INTERNAL_ERROR))
        .hasSize(146);
  }

  @Test
  void 대표_오류_코드의_HTTP_상태가_문서와_같다() {
    assertThat(ErrorCode.INVALID_REQUEST.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(ErrorCode.UNAUTHENTICATED.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(ErrorCode.WEBHOOK_AUTH_FAILED.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(ErrorCode.PAYMENT_FAILED.getStatus()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
    assertThat(ErrorCode.RECONSENT_REQUIRED.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(ErrorCode.RESOURCE_NOT_FOUND.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(ErrorCode.PUBLIC_LINK_EXPIRED.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(ErrorCode.EXPORT_EXPIRED.getStatus()).isEqualTo(HttpStatus.GONE);
    assertThat(ErrorCode.VERSION_CONFLICT.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(ErrorCode.INVALID_TRANSITION.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(ErrorCode.FILE_TOO_LARGE.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    assertThat(ErrorCode.CANDIDATE_LIMIT_EXCEEDED.getStatus())
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(ErrorCode.RATE_LIMITED.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(ErrorCode.VISIT_REQUEST_QUOTA_EXCEEDED.getStatus())
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(ErrorCode.INTERNAL_ERROR.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }

  @Test
  void 상태별_오류_코드_개수가_문서_표와_같다() {
    assertThat(countOf(HttpStatus.BAD_REQUEST)).isEqualTo(10);
    assertThat(countOf(HttpStatus.UNAUTHORIZED)).isEqualTo(5);
    assertThat(countOf(HttpStatus.PAYMENT_REQUIRED)).isEqualTo(3);
    assertThat(countOf(HttpStatus.TOO_MANY_REQUESTS)).isEqualTo(5);
    assertThat(countOf(HttpStatus.CONTENT_TOO_LARGE)).isEqualTo(1);
    assertThat(countOf(HttpStatus.GONE)).isEqualTo(1);
  }

  private long countOf(HttpStatus status) {
    return Arrays.stream(ErrorCode.values()).filter(c -> c.getStatus() == status).count();
  }
}
