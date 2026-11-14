package com.sairo.be.support;

import com.sairo.be.TestcontainersConfiguration;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({TestcontainersConfiguration.class, SecurityProbeController.class})
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "sairo.integrations.kakao-oauth=stub",
      "kakao.admin-key=test-admin-key",
      "kakao.app-id=1234567",
      "sairo.migration.import-executor=sync",
      "sairo.support.scan-dispatch=manual"
    })
public @interface IntegrationTest {}
