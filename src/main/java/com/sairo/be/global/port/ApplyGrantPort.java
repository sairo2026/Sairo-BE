package com.sairo.be.global.port;

import java.util.Optional;

public interface ApplyGrantPort {

  Optional<Long> findActiveGrantId(
      Long granteeOfficeId, Long granteeMembershipId, Long approvalId, Long propertyId);

  /**
   * 매물이 보관될 때 그 매물의 활성 신청 허용을 시스템 회수하고 허용 근거 신청 대기 신청을 상태 이력과 알림을 남겨 종결한다. 호출자의 트랜잭션 안에서만 부른다.
   *
   * @return 회수한 허용 수
   */
  int revokeForArchivedProperty(Long propertyId);
}
