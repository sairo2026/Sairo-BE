package com.sairo.be.domain.billing.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.billing.dto.response.AuthStatusResponse;
import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.entity.SubscriptionStatus;
import com.sairo.be.domain.billing.repository.SubscriptionRepository;
import com.sairo.be.global.security.StaffPrincipal;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
@Transactional(readOnly = true)
public class AuthStatusService {

  private final SubscriptionRepository subscriptionRepository;

  public AuthStatusService(SubscriptionRepository subscriptionRepository) {
    this.subscriptionRepository = subscriptionRepository;
  }

  public AuthStatusResponse get(StaffPrincipal principal) {
    Optional<Subscription> found = subscriptionRepository.findByOfficeId(principal.officeId());
    if (found.isEmpty() || found.get().getStatus() != SubscriptionStatus.SUSPENDED) {
      return new AuthStatusResponse(false, null, null, null);
    }
    Subscription subscription = found.get();
    return new AuthStatusResponse(
        true,
        toDisplayOffset(subscription.getSuspendedAt()),
        toDisplayOffset(subscription.getSuspendedAt().plus(AuthSuspensionService.REAUTH_WINDOW)),
        subscription.getTrialRemainingSeconds());
  }
}
