package com.sairo.be.global.port.prod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sairo.be.global.port.PaymentGateway;
import com.sairo.be.global.port.PaymentGateway.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;

class UnconfiguredPaymentGatewayTest {

  private final UnconfiguredPaymentGateway gateway = new UnconfiguredPaymentGateway();

  @Test
  void 빌링키_발급은_준비되지_않았다는_예외를_던진다() {
    assertThatThrownBy(() -> gateway.issueBillingKey("auth", "customer"))
        .isInstanceOf(PaymentGateway.BillingKeyIssueException.class);
  }

  @Test
  void 결제_계열_호출은_실패_결과를_돌려준다() {
    assertThat(
            gateway.charge(new PaymentGateway.ChargeCommand("k", "c", "o", 1L, "n", "i")).status())
        .isEqualTo(PaymentStatus.FAILED);
    assertThat(gateway.confirm(new PaymentGateway.ConfirmCommand("p", "o", 1L)).failureCode())
        .isEqualTo("PG_NOT_CONFIGURED");
    assertThat(gateway.cancel("p", "r").status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(gateway.inquire("o").status()).isEqualTo(PaymentStatus.FAILED);
  }

  @Test
  void 운영_프로필에서_토스_키가_없을_때만_활성화된다() {
    Profile profile = UnconfiguredPaymentGateway.class.getAnnotation(Profile.class);
    ConditionalOnExpression condition =
        UnconfiguredPaymentGateway.class.getAnnotation(ConditionalOnExpression.class);

    assertThat(profile.value()).containsExactly("prod");
    assertThat(condition.value()).contains("sairo.toss.secret-key").contains("== ''");
  }
}
