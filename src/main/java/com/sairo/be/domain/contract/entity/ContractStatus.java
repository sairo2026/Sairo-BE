package com.sairo.be.domain.contract.entity;

/** 계약 트랙 상태다. 체결 뒤 상태는 담당 공인중개사가 사실 확인 뒤 직접 기록한다. */
public enum ContractStatus {
  DRAFT,
  SIGNED,
  ENDED,
  TERMINATED,
  CANCELLED,
  VOID
}
