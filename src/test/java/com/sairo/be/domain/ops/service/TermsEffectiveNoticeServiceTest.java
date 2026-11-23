package com.sairo.be.domain.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sairo.be.domain.billing.service.SystemJobRunService;
import com.sairo.be.domain.ops.repository.OpsContentRepository;
import com.sairo.be.domain.ops.repository.OpsContentRepository.UpcomingTerms;
import com.sairo.be.global.port.NotificationPublisher;
import com.sairo.be.global.port.NotificationPublisher.Notice;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TermsEffectiveNoticeServiceTest {

  private static final Instant EFFECTIVE = Instant.parse("2026-12-15T15:00:00Z");
  private static final Instant NOW = EFFECTIVE.minus(Duration.ofDays(14));

  private OpsContentRepository repository;
  private NotificationPublisher publisher;
  private SystemJobRunService jobRunService;
  private TermsEffectiveNoticeService service;

  @BeforeEach
  void setUp() {
    repository = mock(OpsContentRepository.class);
    publisher = mock(NotificationPublisher.class);
    jobRunService = mock(SystemJobRunService.class);
    when(jobRunService.runOnce(any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(4).run();
              return true;
            });
    service = new TermsEffectiveNoticeService(repository, publisher, jobRunService);
  }

  private static UpcomingTerms terms(String code, boolean reconsent) {
    return new UpcomingTerms(code, "1.1", EFFECTIVE, reconsent, "문구를 실제 처리에 맞게 고쳤습니다.");
  }

  @Test
  void 이용약관_새_버전은_모든_회원에게_약관_변경_알림을_남긴다() {
    when(repository.upcomingTerms(NOW, EFFECTIVE)).thenReturn(List.of(terms("TOS", false)));
    when(repository.activeUserIds()).thenReturn(List.of(7L, 8L));

    service.sendDue(NOW);

    ArgumentCaptor<Notice> captor = ArgumentCaptor.forClass(Notice.class);
    verify(publisher, org.mockito.Mockito.times(2)).publish(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(Notice::recipientUserId, Notice::type, Notice::targetType)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(7L, "TERMS_CHANGED", "NONE"),
            org.assertj.core.groups.Tuple.tuple(8L, "TERMS_CHANGED", "NONE"));
    assertThat(captor.getValue().message())
        .isEqualTo("2026년 12월 16일부터 사이로 이용약관이 바뀝니다. 주요 변경 내용 문구를 실제 처리에 맞게 고쳤습니다.");
    verify(publisher, never()).publishToOwners(any(), any());
  }

  @Test
  void 구독_구매_약관_새_버전은_끝나지_않은_구독의_대표에게_동의_다시_안내를_남긴다() {
    when(repository.upcomingTerms(NOW, EFFECTIVE))
        .thenReturn(List.of(terms("SUBSCRIPTION_PURCHASE", false)));
    when(repository.officeIdsWithOpenSubscription()).thenReturn(List.of(3L));

    service.sendDue(NOW);

    ArgumentCaptor<Notice> captor = ArgumentCaptor.forClass(Notice.class);
    verify(publisher).publishToOwners(eq(3L), captor.capture());
    assertThat(captor.getValue().type()).isEqualTo("PLAN_CHANGED");
    assertThat(captor.getValue().targetType()).isEqualTo("SUBSCRIPTION");
    assertThat(captor.getValue().message())
        .startsWith("2026년 12월 16일부터 구독·결제 약관이 바뀝니다. 바뀌는 내용 문구를 실제 처리에 맞게 고쳤습니다.")
        .contains("구독 구매 동의를 다시 해 주세요")
        .contains("다음 갱신 결제가 진행되지 않습니다");
    verify(publisher, never()).publish(any());
  }

  @Test
  void 알림은_약관_코드와_버전마다_한_번의_멱등키로_실행하고_기준_시각은_시행_14일_전이다() {
    when(repository.upcomingTerms(NOW, EFFECTIVE)).thenReturn(List.of(terms("PRIVACY", true)));
    when(repository.activeUserIds()).thenReturn(List.of());

    service.sendDue(NOW);

    verify(jobRunService)
        .runOnce(
            eq("terms-effective-notice"),
            eq(null),
            eq("terms-effective-notice:PRIVACY:1.1"),
            eq(NOW),
            any());
  }

  @Test
  void 시행까지_14일보다_많이_남은_행은_조회_범위에_들어오지_않아_아무것도_보내지_않는다() {
    when(repository.upcomingTerms(NOW.minusSeconds(1), EFFECTIVE.minusSeconds(1)))
        .thenReturn(List.of());

    service.sendDue(NOW.minusSeconds(1));

    verify(jobRunService, never()).runOnce(any(), any(), any(), any(), any());
  }

  @Test
  void 개인정보처리방침_안내는_바뀌는_내용을_알린다() {
    assertThat(TermsEffectiveNoticeService.message(terms("PRIVACY", false)))
        .isEqualTo("2026년 12월 16일부터 개인정보처리방침이 바뀝니다. 바뀌는 내용 문구를 실제 처리에 맞게 고쳤습니다.");
  }

  @Test
  void 재동의가_필요한_이용약관_안내에는_동의_안내가_붙는다() {
    assertThat(TermsEffectiveNoticeService.message(terms("TOS", true)))
        .contains("시행 후 첫 로그인 때 변경 내용을 확인하고 동의해 주세요");
  }
}
