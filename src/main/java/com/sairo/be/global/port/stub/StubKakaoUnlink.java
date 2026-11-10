package com.sairo.be.global.port.stub;

import com.sairo.be.global.port.KakaoUnlinkPort;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!prod & !migrate")
@Component
public class StubKakaoUnlink implements KakaoUnlinkPort {

  private final List<String> unlinked = new CopyOnWriteArrayList<>();

  @Override
  public void unlink(String kakaoProviderKey) {
    unlinked.add(kakaoProviderKey);
  }

  public List<String> unlinked() {
    return List.copyOf(unlinked);
  }

  public void clear() {
    unlinked.clear();
  }
}
