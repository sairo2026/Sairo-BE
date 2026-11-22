package com.sairo.be.global.support.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.SairoBeApplication;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 앱이 틀리거나 빠진 Redis 비밀번호로 기동하면 기동 자체가 실패해야 한다(닫힌 상태). 기동하지 않으면 로그인도 세션도 열리지 않고 배포 스크립트의 상태 점검이 통과하지
 * 못해 이전 이미지로 되돌린다. 실패 메시지와 로그에는 비밀번호가 없어야 한다.
 */
@ExtendWith(OutputCaptureExtension.class)
class RedisCredentialStartupFailureTest {

  private static final String WRONG_PASSWORD = "wrong-" + ProtectedRedisEnvironment.PASSWORD;

  private Throwable startWith(String redisPassword) {
    return org.assertj.core.api.Assertions.catchThrowable(
        () -> {
          java.util.List<String> args = new java.util.ArrayList<>();
          args.add("--server.port=0");
          args.add("--sairo.integrations.kakao-oauth=stub");
          args.add("--kakao.admin-key=test-admin-key");
          args.add("--kakao.app-id=1234567");
          args.add("--logging.level.io.lettuce=DEBUG");
          args.add("--logging.level.org.springframework.data.redis=DEBUG");
          args.add("--logging.level.org.springframework.session=DEBUG");
          ProtectedRedisEnvironment.properties(redisPassword)
              .forEach((key, value) -> args.add("--" + key + "=" + value));
          new SpringApplicationBuilder(SairoBeApplication.class)
              .web(WebApplicationType.SERVLET)
              .run(args.toArray(String[]::new))
              .close();
        });
  }

  private static String stackTraceOf(Throwable error) {
    StringWriter writer = new StringWriter();
    error.printStackTrace(new PrintWriter(writer));
    return writer.toString();
  }

  @Test
  void 틀린_Redis_비밀번호면_기동이_실패하고_비밀번호가_노출되지_않는다(CapturedOutput output) {
    Throwable error = startWith(WRONG_PASSWORD);

    assertThat(error).isNotNull();
    assertThat(stackTraceOf(error)).contains("WRONGPASS");
    assertThat(stackTraceOf(error)).doesNotContain(ProtectedRedisEnvironment.PASSWORD);
    assertThat(output.getAll()).doesNotContain(ProtectedRedisEnvironment.PASSWORD);
  }

  @Test
  void Redis_비밀번호가_없으면_기동이_실패하고_비밀번호가_노출되지_않는다(CapturedOutput output) {
    Throwable error = startWith("");

    assertThat(error).isNotNull();
    assertThat(stackTraceOf(error)).containsAnyOf("NOAUTH", "Authentication required");
    assertThat(stackTraceOf(error)).doesNotContain(ProtectedRedisEnvironment.PASSWORD);
    assertThat(output.getAll()).doesNotContain(ProtectedRedisEnvironment.PASSWORD);
  }

  @Test
  void 올바른_비밀번호면_같은_방식으로_기동한다() {
    Throwable error = startWith(ProtectedRedisEnvironment.PASSWORD);

    assertThat(error).isNull();
  }
}
