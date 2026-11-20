package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.repository.AccessContextQueryRepository;
import com.sairo.be.domain.auth.repository.AccessContextQueryRepository.SubscriptionRow;
import com.sairo.be.global.port.OfficeWriteRestriction;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 인증 회수와 미납 제한 이후 단계를 같은 판정으로 보고 사무소 업무 쓰기 제한 여부를 돌려준다. */
@Slf4j
@Profile("!migrate")
@Component
public class OfficeWriteRestrictionJudge implements OfficeWriteRestriction {

  private static final Set<String> RESTRICTED_SUBSCRIPTIONS =
      Set.of("PAST_DUE_RESTRICTED", "SUSPENDED", "ENDED");

  /** 접근 판정(AccessContextService)에서 쓰기를 허용하는 상태다. 이 밖의 값은 막지 않고 기록만 남긴다. */
  private static final Set<String> WRITABLE_SUBSCRIPTIONS =
      Set.of("PRE_PAYMENT", "TRIAL", "ACTIVE", "PAST_DUE_GRACE");

  private final AccessContextQueryRepository queryRepository;

  public OfficeWriteRestrictionJudge(AccessContextQueryRepository queryRepository) {
    this.queryRepository = queryRepository;
  }

  @Override
  public boolean restricted(Long officeId) {
    if (queryRepository.isOfficeRegistrationRevoked(officeId)) {
      return true;
    }
    String status =
        queryRepository.findSubscription(officeId).map(SubscriptionRow::status).orElse(null);
    if (status == null || WRITABLE_SUBSCRIPTIONS.contains(status)) {
      return false;
    }
    if (RESTRICTED_SUBSCRIPTIONS.contains(status)) {
      return true;
    }
    log.warn("알 수 없는 구독 상태라 쓰기를 막지 않았습니다. officeId={}, status={}", officeId, status);
    return false;
  }
}
