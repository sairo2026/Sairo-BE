package com.sairo.be.global.port;

/** 방문 조율의 확정·취소·담당 변경을 홈 업무와 알림함에 반영하는 포트다. */
public interface VisitWorkHooks {

  void scheduleItemConfirmed(Long scheduleItemId);

  void scheduleItemCancelled(Long scheduleItemId);

  void coordinationManagerChanged(Long coordinationId, Long actorMembershipId);
}
