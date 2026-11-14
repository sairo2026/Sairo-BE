package com.sairo.be.global.error;

import java.util.UUID;
import org.slf4j.MDC;

public final class TraceIds {

  public static final String MDC_KEY = "traceId";

  private TraceIds() {}

  public static String currentOrNew() {
    String current = MDC.get(MDC_KEY);
    return current != null ? current : UUID.randomUUID().toString();
  }
}
