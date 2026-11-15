package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.repository.SubscriptionRepository;
import com.sairo.be.global.port.OwnerTransferHooks;
import com.sairo.be.global.support.AdvisoryLock;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 대표 이전이 끝나면 이전 대표가 한 구독 구매 동의를 철회해 새 대표가 다시 동의하게 한다. 구독 상태와 결제수단, 이미 진행 중인 청구서는 바꾸지 않는다. 철회할 동의가
 * 없으면 아무것도 하지 않아 재동의 안내도 보내지 않는다.
 */
@Profile("!migrate")
@Component
public class OwnerTransferConsentReset implements OwnerTransferHooks {

  private final SubscriptionRepository subscriptionRepository;
  private final ConsentWithdrawals consentWithdrawals;
  private final BillingNoticeDispatcher noticeDispatcher;
  private final AdvisoryLock advisoryLock;

  public OwnerTransferConsentReset(
      SubscriptionRepository subscriptionRepository,
      ConsentWithdrawals consentWithdrawals,
      BillingNoticeDispatcher noticeDispatcher,
      AdvisoryLock advisoryLock) {
    this.subscriptionRepository = subscriptionRepository;
    this.consentWithdrawals = consentWithdrawals;
    this.noticeDispatcher = noticeDispatcher;
    this.advisoryLock = advisoryLock;
  }

  @Override
  public void ownerTransferred(Long officeId, Instant now) {
    subscriptionRepository
        .findByOfficeId(officeId)
        .ifPresent(
            found -> {
              advisoryLock.lockForTransaction("billing:subscription:" + found.getId());
              Subscription subscription =
                  subscriptionRepository.findById(found.getId()).orElseThrow();
              if (consentWithdrawals.record(subscription, now)) {
                noticeDispatcher.purchaseConsentReconfirm(officeId);
              }
            });
  }
}
