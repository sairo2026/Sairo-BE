package com.sairo.be.global.port.stub;

import com.sairo.be.global.port.TermsDocumentReader;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 약관 원문 읽기 스텁이다. 외부로 호출하지 않고 시험이 넣어 둔 본문만 돌려준다. 넣지 않은 버전은 읽지 못한 것으로 본다. */
@Profile("!prod & !migrate")
@Component
public class StubTermsDocumentReader implements TermsDocumentReader {

  private final Map<String, String> documents = new ConcurrentHashMap<>();

  @Override
  public Optional<String> read(String termsCode, String termsVersion, String documentUrl) {
    return Optional.ofNullable(documents.get(termsCode + ":" + termsVersion));
  }

  public void put(String termsCode, String termsVersion, String body) {
    documents.put(termsCode + ":" + termsVersion, body);
  }

  public void clear() {
    documents.clear();
  }
}
