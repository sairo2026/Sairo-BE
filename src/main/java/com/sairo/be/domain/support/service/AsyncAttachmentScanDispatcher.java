package com.sairo.be.domain.support.service;

import com.sairo.be.global.support.AfterCommit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Profile("!migrate")
@Component
@ConditionalOnProperty(
    name = "sairo.support.scan-dispatch",
    havingValue = "async",
    matchIfMissing = true)
public class AsyncAttachmentScanDispatcher implements AttachmentScanDispatcher, DisposableBean {

  private final ExecutorService pool =
      Executors.newFixedThreadPool(
          2,
          runnable -> {
            Thread thread = new Thread(runnable, "support-attachment-scan");
            thread.setDaemon(true);
            return thread;
          });
  private final AttachmentScanService scanService;

  public AsyncAttachmentScanDispatcher(AttachmentScanService scanService) {
    this.scanService = scanService;
  }

  @Override
  public void dispatch(long attachmentId) {
    AfterCommit.run(
        () ->
            pool.execute(
                () -> {
                  try {
                    scanService.scan(attachmentId);
                  } catch (RuntimeException e) {
                    log.error("첨부 검사가 예기치 않게 중단됐습니다. attachmentId={}", attachmentId, e);
                  }
                }));
  }

  @Override
  public void destroy() {
    pool.shutdown();
  }
}
