package com.sairo.be.domain.auth.service;

import com.sairo.be.global.port.KakaoFlow;

public enum KakaoAuthFlow {
  LOGIN(KakaoFlow.LOGIN),
  GRANT(KakaoFlow.LOGIN),
  LINK(KakaoFlow.LINK),
  STEP_UP(KakaoFlow.STEP_UP);

  private final KakaoFlow portFlow;

  KakaoAuthFlow(KakaoFlow portFlow) {
    this.portFlow = portFlow;
  }

  public KakaoFlow portFlow() {
    return portFlow;
  }
}
