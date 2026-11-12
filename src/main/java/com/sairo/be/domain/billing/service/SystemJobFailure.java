package com.sairo.be.domain.billing.service;

/** 예외 없이 끝났지만 실패로 남길 작업 결과다. 코드는 system_job_run의 오류 코드가 된다. */
public class SystemJobFailure extends RuntimeException {

  private final String code;

  public SystemJobFailure(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
