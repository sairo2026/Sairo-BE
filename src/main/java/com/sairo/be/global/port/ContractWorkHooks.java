package com.sairo.be.global.port;

/** 계약의 잔금 예정일·임대 만기일·상태·담당자 변경을 홈 업무(CONTRACT_BALANCE, CONTRACT_MATURITY)에 반영하는 포트다. */
public interface ContractWorkHooks {

  /** 계약의 현재 값으로 그 계약의 자동 업무를 만들거나 고치거나 취소한다. 계약 트랜잭션 안에서 부른다. */
  void contractChanged(Long contractId);
}
