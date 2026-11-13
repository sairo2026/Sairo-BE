package com.sairo.be.global.port.stub;

import com.sairo.be.global.port.IntegrationProbe;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 연결 검사 스텁이다. 외부로 호출하지 않는다. 기본은 미구성이고 시험이 구성 상태와 연동별 실패를 정할 수 있다. */
@Profile("!prod & !migrate")
@Component
public class StubIntegrationProbe implements IntegrationProbe {

  private final Map<Long, String> failures = new ConcurrentHashMap<>();
  private volatile boolean configured;

  @Override
  public boolean configured() {
    return configured;
  }

  public void configure(boolean value) {
    configured = value;
  }

  @Override
  public Result probe(Target target) {
    String errorCode = failures.get(target.integrationId());
    return errorCode == null ? Result.ok() : Result.failed(errorCode);
  }

  public void failWith(long integrationId, String errorCode) {
    failures.put(integrationId, errorCode);
  }

  public void clear() {
    failures.clear();
  }
}
