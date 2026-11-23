package com.sairo.be.global.port;

public interface OfficeWriteRestriction {

  /** 인증 회수, 미납 제한, 구독 중지·종료로 사무소 관리 쓰기까지 막아야 하는지 돌려준다. */
  boolean restricted(Long officeId);

  /** 업무 데이터(매물, 고객, 계약, 방문 등) 쓰기를 막아야 하는지 돌려준다. 결제 전 상태도 막는다. */
  boolean businessDataWriteBlocked(Long officeId);
}
