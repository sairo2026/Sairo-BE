package com.sairo.be.global.port.toss;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.global.port.PaymentGateway;
import com.sairo.be.global.port.stub.StubPaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class PaymentGatewaySelectionTest {

  @Configuration(proxyBeanMethods = false)
  static class Support {

    @Bean
    ObjectMapper objectMapper() {
      return JsonMapper.builder().build();
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(Support.class, TossPaymentGateway.class, StubPaymentGateway.class)
          .withPropertyValues("sairo.toss.base-url=http://127.0.0.1:1");

  @Test
  void 시크릿_키가_없으면_스텁을_쓴다() {
    runner
        .withPropertyValues("sairo.toss.secret-key=")
        .run(
            context -> {
              assertThat(context).hasSingleBean(PaymentGateway.class);
              assertThat(context).hasSingleBean(StubPaymentGateway.class);
              assertThat(context).doesNotHaveBean(TossPaymentGateway.class);
            });
  }

  @Test
  void 키_설정이_아예_없어도_스텁을_쓴다() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(StubPaymentGateway.class);
          assertThat(context).doesNotHaveBean(TossPaymentGateway.class);
        });
  }

  @Test
  void 시크릿_키가_있으면_토스_어댑터를_쓰고_스텁은_끈다() {
    runner
        .withPropertyValues("sairo.toss.secret-key=test_sk_fake")
        .run(
            context -> {
              assertThat(context).hasSingleBean(PaymentGateway.class);
              assertThat(context).hasSingleBean(TossPaymentGateway.class);
              assertThat(context).doesNotHaveBean(StubPaymentGateway.class);
            });
  }
}
