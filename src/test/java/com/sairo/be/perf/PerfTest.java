package com.sairo.be.perf;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("perf")
@Import(SqlRecordingConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "sairo.integrations.kakao-oauth=stub",
      "kakao.admin-key=test-admin-key",
      "kakao.app-id=1234567",
      "sairo.migration.import-executor=sync",
      "sairo.support.scan-dispatch=manual",
      "spring.datasource.hikari.maximum-pool-size=12",
      "spring.datasource.hikari.connection-init-sql=SET statement_timeout = 20000"
    })
public @interface PerfTest {}
