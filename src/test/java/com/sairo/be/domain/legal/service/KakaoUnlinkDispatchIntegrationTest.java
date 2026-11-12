package com.sairo.be.domain.legal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.legal.repository.LegalKakaoUnlinkEventRepository;
import com.sairo.be.global.port.KakaoUnlinkPort;
import com.sairo.be.global.support.TextCipher;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.AppUserBuilder;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class KakaoUnlinkDispatchIntegrationTest extends AuthIntegrationTestBase {

  @Autowired private LegalKakaoUnlinkEventRepository repository;
  @Autowired private TextCipher textCipher;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private Clock clock;
  @Autowired private KakaoUnlinkEventService eventService;
  @Autowired private KakaoUnlinkDispatchService stubDispatchService;
  @Autowired private RetentionRuleLookup ruleLookup;

  private AppUser user;

  @BeforeEach
  void createUser() {
    user =
        appUserRepository.saveAndFlush(
            AppUserBuilder.anAppUser().kakaoProviderKey("3001001").build());
  }

  private KakaoUnlinkDispatchService serviceWith(KakaoUnlinkPort port) {
    return new KakaoUnlinkDispatchService(
        repository, port, textCipher, transactionTemplate, ruleLookup, clock);
  }

  private Map<String, Object> event() {
    return jdbcTemplate.queryForMap(
        "SELECT status, attempt_count, next_attempt_at, target_key_encrypted, last_error_code,"
            + " completed_at, key_wiped_at FROM legal_kakao_unlink_event");
  }

  @Test
  void 호출에_성공하면_완료로_닫고_식별자_암호문을_지운다() {
    eventService.enqueueOutbound(user.getId(), "3001001");
    List<String> called = new ArrayList<>();

    int processed = serviceWith(called::add).dispatchDue();

    assertThat(processed).isEqualTo(1);
    assertThat(called).containsExactly("3001001");
    Map<String, Object> row = event();
    assertThat(row.get("status")).isEqualTo("SUCCEEDED");
    assertThat(row.get("attempt_count")).isEqualTo(1);
    assertThat(row.get("target_key_encrypted")).isNull();
    assertThat(row.get("next_attempt_at")).isNull();
    assertThat(row.get("completed_at")).isNotNull();
    assertThat(row.get("key_wiped_at")).isNotNull();
  }

  @Test
  void 실패하면_재시도_상태로_두고_다음_시도_시각을_정한다() {
    eventService.enqueueOutbound(user.getId(), "3001001");

    int processed =
        serviceWith(
                key -> {
                  throw new IllegalStateException("down");
                })
            .dispatchDue();

    assertThat(processed).isZero();
    Map<String, Object> row = event();
    assertThat(row.get("status")).isEqualTo("FAILED_RETRYING");
    assertThat(row.get("attempt_count")).isEqualTo(1);
    assertThat(row.get("last_error_code")).isEqualTo("IllegalStateException");
    assertThat(row.get("target_key_encrypted")).isNotNull();
    Instant next = ((Timestamp) row.get("next_attempt_at")).toInstant();
    assertThat(next).isAfter(Instant.now().plusSeconds(30)).isBefore(Instant.now().plusSeconds(90));
  }

  @Test
  void 다음_시도_시각_전에는_호출하지_않는다() {
    eventService.enqueueOutbound(user.getId(), "3001001");
    jdbcTemplate.update(
        "UPDATE legal_kakao_unlink_event SET next_attempt_at = now() + interval '1 hour'");
    List<String> called = new ArrayList<>();

    assertThat(serviceWith(called::add).dispatchDue()).isZero();
    assertThat(called).isEmpty();
  }

  @Test
  void 재시도_간격은_1분_5분_30분_2시간_12시간_24시간_이후_24시간이다() {
    assertThat(KakaoUnlinkDispatchService.delayAfterFailure(1)).isEqualTo(Duration.ofMinutes(1));
    assertThat(KakaoUnlinkDispatchService.delayAfterFailure(2)).isEqualTo(Duration.ofMinutes(5));
    assertThat(KakaoUnlinkDispatchService.delayAfterFailure(3)).isEqualTo(Duration.ofMinutes(30));
    assertThat(KakaoUnlinkDispatchService.delayAfterFailure(4)).isEqualTo(Duration.ofHours(2));
    assertThat(KakaoUnlinkDispatchService.delayAfterFailure(5)).isEqualTo(Duration.ofHours(12));
    assertThat(KakaoUnlinkDispatchService.delayAfterFailure(6)).isEqualTo(Duration.ofHours(24));
    assertThat(KakaoUnlinkDispatchService.delayAfterFailure(9)).isEqualTo(Duration.ofHours(24));
  }

  @Test
  void 두_번째_실패는_5분_뒤로_잡는다() {
    eventService.enqueueOutbound(user.getId(), "3001001");
    KakaoUnlinkPort failing =
        key -> {
          throw new IllegalStateException("down");
        };
    serviceWith(failing).dispatchDue();
    jdbcTemplate.update(
        "UPDATE legal_kakao_unlink_event SET next_attempt_at = now() - interval '1 minute'");

    serviceWith(failing).dispatchDue();

    Map<String, Object> row = event();
    assertThat(row.get("attempt_count")).isEqualTo(2);
    Instant next = ((Timestamp) row.get("next_attempt_at")).toInstant();
    assertThat(next)
        .isAfter(Instant.now().plusSeconds(240))
        .isBefore(Instant.now().plusSeconds(360));
  }

  @Test
  void 보관_상한_7일이_지나면_포기로_닫고_암호문을_지운다() {
    eventService.enqueueOutbound(user.getId(), "3001001");
    jdbcTemplate.update(
        "UPDATE legal_kakao_unlink_event SET occurred_at = now() - interval '8 days',"
            + " next_attempt_at = now() - interval '1 day'");
    List<String> called = new ArrayList<>();

    serviceWith(called::add).dispatchDue();

    assertThat(called).isEmpty();
    Map<String, Object> row = event();
    assertThat(row.get("status")).isEqualTo("ABANDONED");
    assertThat(row.get("target_key_encrypted")).isNull();
    assertThat(row.get("completed_at")).isNotNull();
    assertThat(row.get("next_attempt_at")).isNull();
  }

  @Test
  void 보관_상한은_보관_기간_규칙_값을_읽어_바꾸면_다음_실행부터_따른다() {
    jdbcTemplate.update(
        "INSERT INTO legal_retention_rule (rule_code, data_group_label, retention_days,"
            + " anchor_event, basis_note) VALUES ('KAKAO_UNLINK_KEY_MAX', '식별자 보관 상한', 3,"
            + " 'RECORDED_AT', '시험')");
    eventService.enqueueOutbound(user.getId(), "3001001");
    jdbcTemplate.update(
        "UPDATE legal_kakao_unlink_event SET occurred_at = now() - interval '4 days',"
            + " next_attempt_at = now() - interval '1 day'");
    List<String> called = new ArrayList<>();

    serviceWith(called::add).dispatchDue();

    assertThat(called).isEmpty();
    assertThat(event().get("status")).isEqualTo("ABANDONED");
  }

  @Test
  void 수신_사건은_호출_대상이_아니다() {
    eventService.recordInbound(
        null,
        "3001001",
        "UNLINK_FROM_APPS",
        com.sairo.be.domain.legal.entity.KakaoUnlinkStatus.NO_MATCH);
    List<String> called = new ArrayList<>();

    assertThat(serviceWith(called::add).dispatchDue()).isZero();
    assertThat(called).isEmpty();
  }

  @Test
  void 기본_연결에서는_스텁_포트가_호출을_기록한다() {
    eventService.enqueueOutbound(user.getId(), "3001002");

    assertThat(stubDispatchService.dispatchDue()).isEqualTo(1);

    assertThat(kakaoUnlink.unlinked()).containsExactly("3001002");
  }
}
