package com.sairo.be.global.port.stub;

import com.sairo.be.global.port.NotificationChannelSender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!prod & !migrate")
@Component
public class StubNotificationChannelSender implements NotificationChannelSender {

  private final List<Delivery> delivered = new CopyOnWriteArrayList<>();

  @Override
  public void deliver(Delivery delivery) {
    delivered.add(delivery);
  }

  public List<Delivery> delivered() {
    return List.copyOf(delivered);
  }

  public void clear() {
    delivered.clear();
  }
}
