package com.sairo.be.domain.ops.service;

import com.sairo.be.global.config.BootstrapOperatorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class BootstrapOperatorRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(BootstrapOperatorRunner.class);

  private final BootstrapOperatorProperties properties;
  private final BootstrapOperatorService service;

  public BootstrapOperatorRunner(
      BootstrapOperatorProperties properties, BootstrapOperatorService service) {
    this.properties = properties;
    this.service = service;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!properties.enabled()) {
      return;
    }
    try {
      service.bootstrap();
    } catch (RuntimeException e) {
      log.error("최초 운영자 초대를 만들지 못했다. 원인 종류: {}", e.getClass().getSimpleName());
    }
  }
}
