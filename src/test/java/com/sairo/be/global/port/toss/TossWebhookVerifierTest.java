package com.sairo.be.global.port.toss;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class TossWebhookVerifierTest {

  private static final String PAYLOAD = "{\"eventType\":\"BILLING_DELETED\"}";
  private static final String TIME = "2026-11-10T10:00:00Z";
  private static final String SECRET = "test_sk_fake";

  private static String signature(String payload, String time, String secret) {
    return Base64.getEncoder().encodeToString(TossWebhookVerifier.sign(payload, time, secret));
  }

  @Test
  void 올바른_서명은_통과한다() {
    String header = "v1:" + signature(PAYLOAD, TIME, SECRET);

    assertThat(TossWebhookVerifier.verify(PAYLOAD, TIME, header, SECRET)).isTrue();
  }

  @Test
  void 서명이_여러_개여도_하나만_맞으면_통과한다() {
    String header =
        "v1:" + signature(PAYLOAD, TIME, "other") + ",v1:" + signature(PAYLOAD, TIME, SECRET);

    assertThat(TossWebhookVerifier.verify(PAYLOAD, TIME, header, SECRET)).isTrue();
  }

  @Test
  void 본문이나_시각이_바뀌면_거절한다() {
    String header = "v1:" + signature(PAYLOAD, TIME, SECRET);

    assertThat(TossWebhookVerifier.verify(PAYLOAD + " ", TIME, header, SECRET)).isFalse();
    assertThat(TossWebhookVerifier.verify(PAYLOAD, "2026-11-10T10:00:01Z", header, SECRET))
        .isFalse();
    assertThat(TossWebhookVerifier.verify(PAYLOAD, TIME, header, "wrong")).isFalse();
  }

  @Test
  void 헤더가_없거나_형식이_깨졌거나_키가_비어_있으면_거절한다() {
    assertThat(TossWebhookVerifier.verify(PAYLOAD, TIME, null, SECRET)).isFalse();
    assertThat(TossWebhookVerifier.verify(PAYLOAD, TIME, "v1:###", SECRET)).isFalse();
    assertThat(TossWebhookVerifier.verify(PAYLOAD, TIME, "v1:AAAA", "")).isFalse();
    assertThat(TossWebhookVerifier.verify(PAYLOAD, null, "v1:abc", SECRET)).isFalse();
  }
}
