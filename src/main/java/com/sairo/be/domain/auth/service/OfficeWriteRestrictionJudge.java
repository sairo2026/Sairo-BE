package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.repository.AccessContextQueryRepository;
import com.sairo.be.domain.auth.repository.AccessContextQueryRepository.SubscriptionRow;
import com.sairo.be.global.port.OfficeWriteRestriction;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 인증 회수와 미납 제한 이후 단계를 같은 판정으로 보고 사무소 업무 쓰기 제한 여부를 돌려준다. */
@Profile("!migrate")
@Component
public class OfficeWriteRestrictionJudge implements OfficeWriteRestriction {

  private static final Set<String> RESTRICTED_SUBSCRIPTIONS =
      Set.of("PAST_DUE_RESTRICTED", "SUSPENDED", "ENDED");

  private final AccessContextQueryRepository queryRepository;

  public OfficeWriteRestrictionJudge(AccessContextQueryRepository queryRepository) {
    this.queryRepository = queryRepository;
  }

  @Override
  public boolean restricted(Long officeId) {
    if (queryRepository.isOfficeRegistrationRevoked(officeId)) {
      return true;
    }
    return queryRepository
        .findSubscription(officeId)
        .map(SubscriptionRow::status)
        .filter(RESTRICTED_SUBSCRIPTIONS::contains)
        .isPresent();
  }
}
