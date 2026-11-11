package com.sairo.be.global.port;

import java.time.Instant;

/** 구성원 상세의 인계될 계약 건수와 소속 변경 때의 계약 담당자 인계다. 진행 중 계약은 초안과 체결 상태다. */
public interface ContractAssignments {

  long countOpen(Long officeId, Long membershipId);

  /** 진행 중 계약의 담당자를 사무소의 승인된 대표로 바꾸고 바꾼 건수를 돌려준다. */
  int handOverToOwner(Long officeId, Long fromMembershipId, Instant now);
}
