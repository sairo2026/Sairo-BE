package com.sairo.be.domain.office.service;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;

final class MembershipNotices {

  private MembershipNotices() {}

  static String approvalNeeded(String applicantName, OfficeMembershipRole role) {
    return applicantName + "님이 " + roleLabel(role) + "로 사무소 참여를 요청했습니다. 요청을 확인하고 승인 여부를 정해 주세요.";
  }

  static String invitation(String officeName, OfficeMembershipRole role) {
    return officeName + " 사무소가 " + roleLabel(role) + "로 초대했습니다. 초대를 확인하고 수락해 주세요.";
  }

  static String suspended(String officeName) {
    return officeLabel(officeName)
        + "에서 접근을 정지했습니다. 정지가 풀릴 때까지 사무소 업무를 이용할 수 없습니다. 자세한 내용은 대표에게 문의해 주세요.";
  }

  static String reinstated(String officeName) {
    return officeLabel(officeName) + "에서 접근 정지를 풀었습니다. 다시 사무소 업무를 이용할 수 있습니다.";
  }

  static String roleChanged(String officeName, OfficeMembershipRole role) {
    String handover =
        role == OfficeMembershipRole.DEPUTY ? " 담당하던 고객, 매물, 업무, 계약은 대표에게 인계되었습니다." : "";
    return officeLabel(officeName) + "에서 회원님의 역할을 " + roleLabel(role) + "로 바꿨습니다." + handover;
  }

  private static String officeLabel(String officeName) {
    return officeName.endsWith("사무소") ? officeName : officeName + " 사무소";
  }

  private static String roleLabel(OfficeMembershipRole role) {
    return switch (role) {
      case OWNER -> "대표";
      case AGENT -> "공인중개사";
      case DEPUTY -> "중개보조원";
    };
  }
}
