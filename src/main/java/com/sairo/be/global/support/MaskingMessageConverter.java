package com.sairo.be.global.support;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

public class MaskingMessageConverter extends MessageConverter {

  @Override
  public String convert(ILoggingEvent event) {
    return LogMasking.maskSensitive(event.getFormattedMessage());
  }
}
