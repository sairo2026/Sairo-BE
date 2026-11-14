package com.sairo.be.global.port.stub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sairo.be.global.port.MailSender.MailMessage;
import com.sairo.be.global.port.PaymentGateway.ChargeCommand;
import com.sairo.be.global.port.PaymentGateway.PaymentStatus;
import com.sairo.be.global.port.SmsSender.SmsMessage;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PortStubTest {

  @Test
  void 메일_스텁은_보낸_메일을_기록한다() {
    StubMailSender sender = new StubMailSender();

    sender.send(new MailMessage("a@example.com", "제목", "본문"));

    assertThat(sender.sent()).containsExactly(new MailMessage("a@example.com", "제목", "본문"));
    sender.clear();
    assertThat(sender.sent()).isEmpty();
  }

  @Test
  void 문자_스텁은_보낸_문자를_기록한다() {
    StubSmsSender sender = new StubSmsSender();

    sender.send(new SmsMessage("01000000000", "123456"));

    assertThat(sender.sent()).hasSize(1);
  }

  @Test
  void 저장소_스텁은_저장_조회_삭제와_기한_있는_주소_발급을_지원한다() {
    InMemoryObjectStorage storage = new InMemoryObjectStorage();
    byte[] content = {1, 2, 3};

    storage.put("evidence/1", content, "application/pdf");
    content[0] = 9;

    assertThat(storage.find("evidence/1")).contains(new byte[] {1, 2, 3});
    assertThat(storage.issueDownloadUrl("evidence/1", Duration.ofMinutes(5)).toString())
        .contains("evidence/1")
        .contains("ttl=300");
    storage.delete("evidence/1");
    assertThat(storage.find("evidence/1")).isEmpty();
    assertThatThrownBy(() -> storage.issueDownloadUrl("evidence/1", Duration.ofMinutes(5)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 결제_스텁은_응답_상태를_바꿀_수_있고_요청을_기록한다() {
    StubPaymentGateway gateway = new StubPaymentGateway();
    ChargeCommand command =
        new ChargeCommand("billing", "customer", "order-1", 10_000, "구독", "key-1");

    assertThat(gateway.charge(command).status()).isEqualTo(PaymentStatus.SUCCEEDED);
    gateway.respondWith(PaymentStatus.FAILED);
    var failed = gateway.charge(command);
    assertThat(failed.status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(failed.failureCode()).isNotNull();
    gateway.respondWith(PaymentStatus.VERIFYING);
    assertThat(gateway.charge(command).status()).isEqualTo(PaymentStatus.VERIFYING);
    assertThat(gateway.charges()).hasSize(3);
    gateway.reset();
    assertThat(gateway.charges()).isEmpty();
  }

  @Test
  void 카카오_스텁은_코드에서_사용자_ID를_복원하고_연결_끊기를_기록한다() {
    StubKakaoOAuth oauth = new StubKakaoOAuth();
    StubKakaoUnlink unlink = new StubKakaoUnlink();

    assertThat(oauth.fetchUserId(oauth.exchangeToken("5001001"))).isEqualTo(5001001L);
    unlink.unlink("5001001");
    assertThat(unlink.unlinked()).containsExactly("5001001");
  }
}
