package com.sairo.be.global.port;

/** 알림함 알림을 앱 밖(푸시·문자·메일)으로 전달하는 포트다. 고객 개인정보와 계약 파일은 담지 않는다. */
public interface NotificationChannelSender {

  void deliver(Delivery delivery);

  record Delivery(Long notificationId, Long recipientUserId, String type, String message) {}
}
