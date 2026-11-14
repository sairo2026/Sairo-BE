package com.sairo.be.global.port.prod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sairo.be.global.port.PaymentGateway;
import com.sairo.be.global.port.toss.TossPaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class UnavailablePaymentGatewayTest {

  @Configuration(proxyBeanMethods = false)
  static class Support {
    @Bean
    ObjectMapper objectMapper() {
      return JsonMapper.builder().build();
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              Support.class, TossPaymentGateway.class, UnavailablePaymentGateway.class)
          .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"));

  @Test
  void noTossKeyStartsWithFailClosedGateway() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(PaymentGateway.class);
          assertThat(context).hasSingleBean(UnavailablePaymentGateway.class);
          assertThat(context).doesNotHaveBean(TossPaymentGateway.class);
          PaymentGateway gateway = context.getBean(PaymentGateway.class);
          assertThatThrownBy(() -> gateway.issueBillingKey("auth", "customer"))
              .isInstanceOf(PaymentGateway.BillingKeyIssueException.class)
              .hasMessage("PAYMENT_PROVIDER_NOT_CONFIGURED");
          assertThat(gateway.inquire("order").status())
              .isEqualTo(PaymentGateway.PaymentStatus.FAILED);
        });
  }

  @Test
  void tossKeySelectsRealGateway() {
    runner
        .withPropertyValues(
            "sairo.toss.secret-key=test_sk_fake", "sairo.toss.base-url=http://127.0.0.1:1")
        .run(
            context -> {
              assertThat(context).hasSingleBean(PaymentGateway.class);
              assertThat(context).hasSingleBean(TossPaymentGateway.class);
              assertThat(context).doesNotHaveBean(UnavailablePaymentGateway.class);
            });
  }
}
