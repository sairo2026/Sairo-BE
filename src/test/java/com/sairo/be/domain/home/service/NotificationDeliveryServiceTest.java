package com.sairo.be.domain.home.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sairo.be.domain.home.entity.NotificationType;
import com.sairo.be.domain.home.repository.NotificationDeliveryRepository;
import com.sairo.be.domain.home.repository.NotificationDeliveryRepository.DueRow;
import com.sairo.be.global.port.MailSender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationDeliveryServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-14T00:00:00Z");

  private final NotificationDeliveryRepository repository =
      mock(NotificationDeliveryRepository.class);
  private final MailSender mailSender = mock(MailSender.class);
  private final NotificationDeliveryService service =
      new NotificationDeliveryService(repository, mailSender, Clock.fixed(NOW, ZoneOffset.UTC));

  private static DueRow row(int attempts) {
    return new DueRow(7L, 70L, "EMAIL", attempts, 3L, "TERMS_CHANGED", "약관이 바뀝니다.\n자세한 내용");
  }

  @Test
  void 실패_뒤_간격은_5분_30분_2시간이고_그_뒤에는_없다() {
    assertThat(NotificationDeliveryService.delayAfterFailure(1)).contains(Duration.ofMinutes(5));
    assertThat(NotificationDeliveryService.delayAfterFailure(2)).contains(Duration.ofMinutes(30));
    assertThat(NotificationDeliveryService.delayAfterFailure(3)).contains(Duration.ofHours(2));
    assertThat(NotificationDeliveryService.delayAfterFailure(4)).isEmpty();
  }

  @Test
  void 이메일로_전달하는_알림_종류마다_전용_제목이_있다() {
    for (NotificationType type : NotificationType.values()) {
      if (type.deliversByEmail()) {
        assertThat(NotificationDeliveryService.subjectOf(type.name()))
            .startsWith("[사이로] ")
            .isNotEqualTo(NotificationDeliveryService.DEFAULT_SUBJECT);
      }
    }
  }

  @Test
  void 종류별_제목은_정해진_문구다() {
    assertThat(NotificationDeliveryService.subjectOf("OFFICE_REVIEW")).isEqualTo("[사이로] 사무소 인증 안내");
    assertThat(NotificationDeliveryService.subjectOf("MEMBERSHIP_APPROVAL_NEEDED"))
        .isEqualTo("[사이로] 사무소 참여 승인 요청이 도착했습니다");
    assertThat(NotificationDeliveryService.subjectOf("TRIAL_ENDING"))
        .isEqualTo("[사이로] 무료 체험 종료 안내");
    assertThat(NotificationDeliveryService.subjectOf("PLAN_CHANGED")).isEqualTo("[사이로] 구독 변경 안내");
    assertThat(NotificationDeliveryService.subjectOf("PAYMENT")).isEqualTo("[사이로] 결제·환불 안내");
    assertThat(NotificationDeliveryService.subjectOf("RETURN_SCHEDULED"))
        .isEqualTo("[사이로] 데이터 반환 예정 안내");
    assertThat(NotificationDeliveryService.subjectOf("DELETION_SCHEDULED"))
        .isEqualTo("[사이로] 삭제 예정 안내");
    assertThat(NotificationDeliveryService.subjectOf("TERMS_CHANGED")).isEqualTo("[사이로] 약관 변경 안내");
    assertThat(NotificationDeliveryService.subjectOf("OWNER_TRANSFER")).isEqualTo("[사이로] 대표 이전 안내");
  }

  @Test
  void 알_수_없는_종류와_이메일_대상이_아닌_종류는_서비스_안내_제목을_쓴다() {
    assertThat(NotificationDeliveryService.subjectOf("UNKNOWN_TYPE")).isEqualTo("[사이로] 서비스 안내");
    assertThat(NotificationDeliveryService.subjectOf("NOTICE")).isEqualTo("[사이로] 서비스 안내");
    assertThat(NotificationDeliveryService.subjectOf(null)).isEqualTo("[사이로] 서비스 안내");
  }

  @Test
  void 메일_제목은_본문을_자르지_않고_종류_제목을_쓰며_본문은_그대로_보낸다() {
    when(repository.claimDue(NOW, NotificationDeliveryService.BATCH_LIMIT))
        .thenReturn(List.of(row(0)));
    when(repository.recipientEmail(3L)).thenReturn(Optional.of("user@example.com"));

    service.deliverDue();

    ArgumentCaptor<MailSender.MailMessage> captor =
        ArgumentCaptor.forClass(MailSender.MailMessage.class);
    verify(mailSender).send(captor.capture());
    assertThat(captor.getValue().subject()).isEqualTo("[사이로] 약관 변경 안내");
    assertThat(captor.getValue().body())
        .isEqualTo("약관이 바뀝니다.\n자세한 내용" + NotificationDeliveryService.FOOTER);
  }

  @Test
  void 전송에_성공하면_전달_완료로_표시한다() {
    when(repository.claimDue(NOW, NotificationDeliveryService.BATCH_LIMIT))
        .thenReturn(List.of(row(0)));
    when(repository.recipientEmail(3L)).thenReturn(Optional.of("user@example.com"));

    assertThat(service.deliverDue()).isEqualTo(1);

    verify(mailSender).send(any());
    verify(repository).markDelivered(7L, 1, NOW);
  }

  @Test
  void 첫_실패는_5분_뒤_재시도로_잡는다() {
    when(repository.claimDue(NOW, NotificationDeliveryService.BATCH_LIMIT))
        .thenReturn(List.of(row(0)));
    when(repository.recipientEmail(3L)).thenReturn(Optional.of("user@example.com"));
    doThrow(new IllegalStateException("보내지 못했다")).when(mailSender).send(any());

    service.deliverDue();

    verify(repository)
        .markRetry(7L, 1, NOW, NOW.plus(Duration.ofMinutes(5)), "IllegalStateException");
    verify(repository, never()).recordAbandoned(any(), any(), any());
  }

  @Test
  void 세_번의_재시도가_모두_실패하면_전달_포기와_시스템_작업_실패를_남긴다() {
    when(repository.claimDue(NOW, NotificationDeliveryService.BATCH_LIMIT))
        .thenReturn(List.of(row(3)));
    when(repository.recipientEmail(3L)).thenReturn(Optional.of("user@example.com"));
    doThrow(new IllegalStateException("보내지 못했다")).when(mailSender).send(any());

    service.deliverDue();

    verify(repository).markTerminal(7L, "ABANDONED", 4, NOW, "IllegalStateException");
    verify(repository).recordAbandoned(7L, NOW, "DELIVERY_ABANDONED");
  }

  @Test
  void 받는_주소가_없으면_재시도하지_않고_실패로_끝낸다() {
    when(repository.claimDue(NOW, NotificationDeliveryService.BATCH_LIMIT))
        .thenReturn(List.of(row(0)));
    when(repository.recipientEmail(3L)).thenReturn(Optional.empty());

    service.deliverDue();

    verify(mailSender, never()).send(any());
    verify(repository).markTerminal(eq(7L), eq("FAILED"), eq(1), eq(NOW), eq("NO_RECIPIENT_EMAIL"));
  }
}
