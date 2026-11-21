package com.sairo.be.e2e;

import com.sairo.be.global.port.KakaoOAuthPort;
import com.sairo.be.global.port.PaymentGateway;
import com.sairo.be.global.port.stub.StubKakaoOAuth;
import com.sairo.be.global.port.stub.StubPaymentGateway;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Refuse to start a real API E2E server wired to a live integration. */
@Configuration(proxyBeanMethods = false)
@Profile("local-e2e")
class E2EIntegrationIsolation {

  E2EIntegrationIsolation(KakaoOAuthPort kakao, PaymentGateway payments) {
    if (!(kakao instanceof StubKakaoOAuth) || !(payments instanceof StubPaymentGateway)) {
      throw new IllegalStateException("local-e2e requires stub OAuth and payment integrations");
    }
  }
}
