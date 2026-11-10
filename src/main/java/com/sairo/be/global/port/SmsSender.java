package com.sairo.be.global.port;

public interface SmsSender {

  void send(SmsMessage message);

  record SmsMessage(String toPhone, String body) {}
}
