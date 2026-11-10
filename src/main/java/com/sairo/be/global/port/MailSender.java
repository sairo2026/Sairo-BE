package com.sairo.be.global.port;

public interface MailSender {

  void send(MailMessage message);

  record MailMessage(String to, String subject, String body) {}
}
