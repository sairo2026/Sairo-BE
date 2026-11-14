package com.sairo.be.global.port.stub;

import com.sairo.be.global.port.MailSender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!prod & !migrate")
@Component
public class StubMailSender implements MailSender {

  private final List<MailMessage> sent = new CopyOnWriteArrayList<>();

  @Override
  public void send(MailMessage message) {
    sent.add(message);
  }

  public List<MailMessage> sent() {
    return List.copyOf(sent);
  }

  public void clear() {
    sent.clear();
  }
}
