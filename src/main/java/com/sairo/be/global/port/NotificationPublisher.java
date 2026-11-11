package com.sairo.be.global.port;

import java.util.List;
import java.util.Optional;

/** 알림함(notification 표)에 수신자별 알림을 남기는 진입점이다. 외부 전달은 NotificationChannelSender가 맡는다. */
public interface NotificationPublisher {

  /** 수신 설정으로 꺼진 종류면 아무것도 남기지 않고 빈 값을 돌려준다. */
  Optional<Long> publish(Notice notice);

  /** 소속 ID의 사용자에게 남긴다. notice의 recipientUserId는 무시한다. */
  Optional<Long> publishToMembership(Long membershipId, Notice notice);

  /** 사무소의 승인된 대표 모두에게 같은 알림을 남긴다. */
  List<Long> publishToOwners(Long officeId, Notice notice);

  record Notice(
      Long officeId,
      Long recipientUserId,
      String type,
      String message,
      String targetType,
      Long targetId) {}
}
