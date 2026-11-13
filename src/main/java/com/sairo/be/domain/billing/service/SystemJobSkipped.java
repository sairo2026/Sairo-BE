package com.sairo.be.domain.billing.service;

/** 실행할 수단이 없어 건너뛴 작업 결과다. 실행은 성공으로 끝나고 코드는 system_job_run의 오류 코드에 남는다. */
public class SystemJobSkipped extends RuntimeException {

  public static final String NOT_CONFIGURED = "NOT_CONFIGURED";

  private final String code;

  public SystemJobSkipped(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
