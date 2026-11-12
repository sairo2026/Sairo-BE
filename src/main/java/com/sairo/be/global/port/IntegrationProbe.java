package com.sairo.be.global.port;

/**
 * 외부 연동 연결 검사 포트다(OPS-09). 실구현은 공급자별 점검 호출만 하며 실제 과금이나 운영 고객 발송을 일으키지 않는다. 비밀값은 받지 않고 비밀관리 저장소의 참조
 * ID만 받는다.
 */
public interface IntegrationProbe {

  Result probe(Target target);

  record Target(
      long integrationId,
      String environment,
      String provider,
      String functionCode,
      String secretReferenceId) {}

  /** 검사 결과다. 실패면 오류 코드가 있다. */
  record Result(boolean success, String errorCode) {

    public static Result ok() {
      return new Result(true, null);
    }

    public static Result failed(String errorCode) {
      return new Result(false, errorCode);
    }
  }
}
