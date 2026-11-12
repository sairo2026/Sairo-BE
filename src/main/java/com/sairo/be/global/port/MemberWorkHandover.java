package com.sairo.be.global.port;

import java.time.Instant;

/**
 * 탈퇴·나가기·회수·접근정지·역할 강등 때 구성원이 맡던 진행 중 업무를 대표에게 넘긴다. 업무 종류마다 그 업무를 가진 도메인이 구현한다. 계약은 {@link
 * ContractAssignments}가 맡는다. 과거 담당 기록(결과 이력, 상담 기록 작성자 등)은 바꾸지 않는다.
 */
public interface MemberWorkHandover {

  enum WorkKind {
    CUSTOMER,
    PROPERTY,
    TASK,
    VISIT
  }

  WorkKind kind();

  /** 구성원 상세의 인계될 업무 건수다. */
  long countOpen(Long officeId, Long membershipId);

  /** 진행 중 업무의 담당자를 바꾸고 바꾼 건수를 돌려준다. 호출한 쪽의 트랜잭션 안에서 부른다. */
  int handOver(Long officeId, Long fromMembershipId, Long toMembershipId, Instant now);
}
