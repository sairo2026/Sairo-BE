package com.sairo.be.global.port;

import java.time.Instant;

/** 대표 이전 완료가 구독·결제에 미치는 영향을 반영하는 포트다. */
public interface OwnerTransferHooks {

  /** 대표 이전 완료 트랜잭션 안에서 부른다. 사무소의 유효 구매 동의를 철회하고 새 대표에게 재동의를 안내한다. */
  void ownerTransferred(Long officeId, Instant now);
}
