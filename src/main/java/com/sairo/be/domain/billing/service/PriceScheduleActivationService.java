package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.repository.PriceScheduleActivationRepository;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인으로 예약(SCHEDULED)이 된 요금표 버전과 체험 이벤트를 효력·시작 시각이 지나면 시행·활성(ACTIVE)으로 바꾸는 배치다. 요금 선택과 무료 기간 부여는
 * 시행·활성 행만 고르므로 결제 배치보다 먼저 돈다. 이전 시행 버전은 종료로 바꾸지 않는다(선택은 효력 시각이 가장 늦은 시행 버전을 쓴다).
 */
@Profile("!migrate")
@Service
public class PriceScheduleActivationService {

  private final PriceScheduleActivationRepository repository;

  public PriceScheduleActivationService(PriceScheduleActivationRepository repository) {
    this.repository = repository;
  }

  public record Activation(int priceSchedules, int trialEvents) {}

  @Transactional
  public Activation activateDue(Instant now) {
    return new Activation(
        repository.activateDuePriceSchedules(now), repository.activateDueTrialEvents(now));
  }
}
