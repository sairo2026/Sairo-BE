package com.sairo.be.domain.home.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
  void 제목은_첫_줄을_쓰고_예순_자로_자른다() {
    assertThat(NotificationDeliveryService.subjectOf("약관이 바뀝니다.\n자세한 내용"))
        .isEqualTo("[사이로] 약관이 바뀝니다.");
    assertThat(NotificationDeliveryService.subjectOf("가".repeat(100)))
        .isEqualTo("[사이로] " + "가".repeat(60));
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
