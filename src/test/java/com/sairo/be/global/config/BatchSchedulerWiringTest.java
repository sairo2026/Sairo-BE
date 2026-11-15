package com.sairo.be.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.sairo.be.domain.billing.service.BillingJobService;
import com.sairo.be.domain.billing.service.BillingScheduler;
import com.sairo.be.domain.billing.service.PlanNoticeJobService;
import com.sairo.be.domain.billing.service.PriceScheduleActivationService;
import com.sairo.be.domain.billing.service.TaxDocumentPendingScanService;
import com.sairo.be.domain.billing.service.TaxDocumentScheduler;
import com.sairo.be.domain.office.service.MembershipExpiryScheduler;
import com.sairo.be.domain.office.service.MembershipExpiryService;
import com.sairo.be.domain.office.service.OwnerTransferService;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

class BatchSchedulerWiringTest {

  private static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private static void assertBatchSwitch(Class<?> type) {
    ConditionalOnProperty condition = type.getAnnotation(ConditionalOnProperty.class);
    assertThat(condition).as(type.getSimpleName()).isNotNull();
    assertThat(condition.name()).containsExactly("sairo.batch.enabled");
    assertThat(condition.havingValue()).isEqualTo("true");
  }

  @Test
  void 요금_배치는_개정_적용_청구_작업_요금_안내_순서로_5분_간격에_잠금과_함께_돈다() throws Exception {
    PriceScheduleActivationService activation = mock(PriceScheduleActivationService.class);
    BillingJobService jobs = mock(BillingJobService.class);
    PlanNoticeJobService notices = mock(PlanNoticeJobService.class);
    new BillingScheduler(activation, jobs, notices, CLOCK).run();
    InOrder order = inOrder(activation, jobs, notices);
    order.verify(activation).activateDue(NOW);
    order.verify(jobs).runAll(NOW);
    order.verify(notices).runAll(NOW);

    Method run = BillingScheduler.class.getMethod("run");
    assertThat(run.getAnnotation(Scheduled.class).fixedDelayString()).isEqualTo("PT5M");
    assertThat(run.getAnnotation(SchedulerLock.class).name()).isEqualTo("billing-cycle");
    assertBatchSwitch(BillingScheduler.class);
  }

  @Test
  void 구성원_만료_배치는_매시_5분에_가입_요청_정리와_대표_이전_만료를_함께_처리한다() throws Exception {
    MembershipExpiryService expiry = mock(MembershipExpiryService.class);
    OwnerTransferService transfers = mock(OwnerTransferService.class);
    new MembershipExpiryScheduler(expiry, transfers).run();
    verify(expiry).expireOverdue();
    verify(transfers).expireOverdue();

    Method run = MembershipExpiryScheduler.class.getMethod("run");
    Scheduled scheduled = run.getAnnotation(Scheduled.class);
    assertThat(scheduled.cron()).isEqualTo("0 5 * * * *");
    assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
    assertThat(run.getAnnotation(SchedulerLock.class).name()).isEqualTo("membership-expiry");
    assertBatchSwitch(MembershipExpiryScheduler.class);
  }

  @Test
  void 세금_증빙_배치는_매일_9시_KST에_발급_대기_스캔을_현재_시각으로_돈다() throws Exception {
    TaxDocumentPendingScanService scan = mock(TaxDocumentPendingScanService.class);
    new TaxDocumentScheduler(scan, CLOCK).scan();
    verify(scan).runScheduled(NOW);

    Method scanMethod = TaxDocumentScheduler.class.getMethod("scan");
    Scheduled scheduled = scanMethod.getAnnotation(Scheduled.class);
    assertThat(scheduled.cron()).isEqualTo("0 0 9 * * *");
    assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
    assertThat(scanMethod.getAnnotation(SchedulerLock.class).name())
        .isEqualTo("tax-document-pending-scan");
    assertBatchSwitch(TaxDocumentScheduler.class);
  }
}
