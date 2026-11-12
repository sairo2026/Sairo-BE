package com.sairo.be.global.port;

import java.util.Optional;

/** 게시된 약관 원문을 읽는 포트다(OPS-18). 읽지 못하면 비어 있다. 원문은 해시 대조에만 쓰고 저장하지 않는다. */
public interface TermsDocumentReader {

  Optional<String> read(String termsCode, String termsVersion, String documentUrl);
}
