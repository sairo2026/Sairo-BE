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

  private static String roleLabel(OfficeMembershipRole role) {
    return switch (role) {
      case OWNER -> "대표";
      case AGENT -> "공인중개사";
      case DEPUTY -> "중개보조원";
    };
  }
}
