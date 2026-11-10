package com.sairo.be.devseed;

import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@TestConfiguration(proxyBeanMethods = false)
public class ReviewSeedConfiguration {

  private static final Logger log = LoggerFactory.getLogger(ReviewSeedConfiguration.class);

  @Bean
  ApplicationRunner reviewAccountSeedRunner(
      AppUserRepository appUserRepository,
      OfficeRepository officeRepository,
      OfficeMembershipRepository membershipRepository,
      PasswordEncoder passwordEncoder,
      JdbcTemplate jdbc) {
    return args -> {
      String email = System.getenv("REVIEW_ACCOUNT_EMAIL");
      String password = System.getenv("REVIEW_ACCOUNT_PASSWORD");
      if (password == null || password.isBlank()) {
        log.warn("REVIEW_ACCOUNT_PASSWORD is not set. The review account was not created.");
        return;
      }
      ReviewAccountSeeder.Result result =
          new ReviewAccountSeeder(
                  appUserRepository, officeRepository, membershipRepository, passwordEncoder, jdbc)
              .seed(email == null || email.isBlank() ? "review@example.com" : email, password);
      log.info("Review account seed finished. created={}", result.created());
    };
  }
}
