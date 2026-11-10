package com.sairo.be.devseed;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.domain.auth.service.PasswordPolicy;
import com.sairo.be.domain.office.entity.Office;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.support.fixture.BillingFixtures;
import com.sairo.be.support.fixture.OfficeBuilder;
import com.sairo.be.support.fixture.TestData;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

public class ReviewAccountSeeder {

  private final AppUserRepository appUserRepository;
  private final OfficeRepository officeRepository;
  private final OfficeMembershipRepository membershipRepository;
  private final PasswordEncoder passwordEncoder;
  private final JdbcTemplate jdbc;

  public ReviewAccountSeeder(
      AppUserRepository appUserRepository,
      OfficeRepository officeRepository,
      OfficeMembershipRepository membershipRepository,
      PasswordEncoder passwordEncoder,
      JdbcTemplate jdbc) {
    this.appUserRepository = appUserRepository;
    this.officeRepository = officeRepository;
    this.membershipRepository = membershipRepository;
    this.passwordEncoder = passwordEncoder;
    this.jdbc = jdbc;
  }

  public record Result(boolean created, Long userId, Long officeId) {}

  public Result seed(String email, String password) {
    validate(email, password);
    if (appUserRepository.findByEmail(email).isPresent()) {
      return new Result(false, null, null);
    }
    BillingFixtures fixtures = new BillingFixtures(jdbc);
    Instant now = Instant.now();
    ensureActivePrice(fixtures, now);
    long eventId = ensureTrialEvent(fixtures, now);

    AppUser user =
        appUserRepository.saveAndFlush(
            AppUser.registerWithEmail(
                email, passwordEncoder.encode(password), "심사 계정", "010-9000-0001", now, now));
    Office office =
        officeRepository.saveAndFlush(
            OfficeBuilder.anOffice().name("심사용 공인중개사사무소").representativeName("심사 계정").build());
    new TestData(appUserRepository, officeRepository, membershipRepository)
        .approvedStaffIn(office, user, OfficeMembershipRole.OWNER);
    fixtures
        .subscription(office.getId())
        .status("TRIAL")
        .trial(eventId, now.minus(1, ChronoUnit.DAYS), now.plus(59, ChronoUnit.DAYS))
        .insert();
    return new Result(true, user.getId(), office.getId());
  }

  private static void validate(String email, String password) {
    if (email == null || email.isBlank() || !email.contains("@")) {
      throw new IllegalArgumentException("review account email is required");
    }
    int length = password == null ? 0 : password.codePointCount(0, password.length());
    if (length < PasswordPolicy.MIN_LENGTH || length > PasswordPolicy.MAX_LENGTH) {
      throw new IllegalArgumentException("review account password must follow the password policy");
    }
  }

  private void ensureActivePrice(BillingFixtures fixtures, Instant now) {
    Integer active =
        jdbc.queryForObject(
            "SELECT count(*) FROM price_schedule WHERE status = 'ACTIVE' AND audience = 'STANDARD'",
            Integer.class);
    if (active == null || active == 0) {
      fixtures.standardPrice("review-seed-" + now.toEpochMilli(), now.minus(30, ChronoUnit.DAYS));
    }
  }

  private long ensureTrialEvent(BillingFixtures fixtures, Instant now) {
    List<Long> events =
        jdbc.queryForList(
            "SELECT id FROM trial_event WHERE status = 'ACTIVE' AND audience = 'GENERAL'"
                + " ORDER BY id LIMIT 1",
            Long.class);
    if (!events.isEmpty()) {
      return events.get(0);
    }
    return fixtures.trialEvent(
        "GENERAL",
        "DURATION",
        now.minus(30, ChronoUnit.DAYS),
        now.plus(300, ChronoUnit.DAYS),
        null,
        "ACTIVE");
  }
}
