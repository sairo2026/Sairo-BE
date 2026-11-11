package com.sairo.be.global.port.stub;

import com.sairo.be.global.port.AttachmentScanner;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!prod & !migrate")
@Component
public class StubAttachmentScanner implements AttachmentScanner {

  public static final String REJECT_MARKER = "EICAR-STANDARD-ANTIVIRUS-TEST-FILE";
  public static final String UNAVAILABLE_MARKER = "SCANNER-UNAVAILABLE";

  @Override
  public Verdict scan(String fileName, String mediaType, byte[] content) {
    String text = new String(content, StandardCharsets.ISO_8859_1);
    if (text.contains(UNAVAILABLE_MARKER)) {
      throw new IllegalStateException("검사기를 사용할 수 없습니다.");
    }
    return text.contains(REJECT_MARKER) ? Verdict.REJECTED : Verdict.CLEAN;
  }
}
