package com.sairo.be.global.port.stub;

import com.sairo.be.global.port.SmsSender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!prod & !migrate")
@Component
public class StubSmsSender implements SmsSender {

  private final List<SmsMessage> sent = new CopyOnWriteArrayList<>();

  @Override
  public void send(SmsMessage message) {
    sent.add(message);
  }

  public List<SmsMessage> sent() {
    return List.copyOf(sent);
  }

  public void clear() {
    sent.clear();
  }
}
