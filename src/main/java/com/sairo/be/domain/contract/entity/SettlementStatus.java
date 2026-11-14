package com.sairo.be.domain.contract.entity;

/** 잔금 트랙 상태다. UNDECIDED는 계약을 만들 때의 초기값이라 요청으로 지정하지 않는다. */
public enum SettlementStatus {
  UNDECIDED,
  SCHEDULED,
  PARTIAL,
  COMPLETE,
  NONE
}
