package com.sairo.be.domain.support.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
@ConditionalOnProperty(name = "sairo.support.scan-dispatch", havingValue = "manual")
public class ManualAttachmentScanDispatcher implements AttachmentScanDispatcher {

  @Override
  public void dispatch(long attachmentId) {}
}
