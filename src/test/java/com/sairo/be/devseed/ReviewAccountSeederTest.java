package com.sairo.be.devseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

@IntegrationTest
class ReviewAccountSeederTest extends AuthIntegrationTestBase {

  private static final String EMAIL = "review-seed@example.com";
  private static final String SEED_PASSWORD = "Rv-4829-xq";

  @Autowired private OfficeRepository officeRepository;
  @Autowired private OfficeMembershipRepository membershipRepository;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private AppUserRepository userRepository;

  private ReviewAccountSeeder seeder() {
    return new ReviewAccountSeeder(
        userRepository, officeRepository, membershipRepository, passwordEncoder, jdbcTemplate);
  }

  @Test
  void 시드한_계정은_로그인되고_무료체험_구독을_가진_대표다() throws Exception {
    ReviewAccountSeeder.Result result = seeder().seed(EMAIL, SEED_PASSWORD);

    assertThat(result.created()).isTrue();
    Cookie cookie = login(EMAIL, SEED_PASSWORD);
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/subscriptions/current")
                .cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("TRIAL"))
        .andExpect(jsonPath("$.readiness.hasBillingKey").value(false));
  }

  @Test
  void 같은_계정을_다시_시드해도_중복을_만들지_않는다() {
    seeder().seed(EMAIL, SEED_PASSWORD);

    ReviewAccountSeeder.Result second = seeder().seed(EMAIL, SEED_PASSWORD);

    assertThat(second.created()).isFalse();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app_user WHERE email = ?", Integer.class, EMAIL))
        .isEqualTo(1);
  }

  @Test
  void 비밀번호_정책에_맞지_않으면_만들지_않는다() {
    assertThatThrownBy(() -> seeder().seed(EMAIL, "short"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> seeder().seed(EMAIL, "this-password-is-too-long"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
