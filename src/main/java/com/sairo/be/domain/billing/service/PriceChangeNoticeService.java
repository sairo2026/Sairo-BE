package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.dto.response.PriceChangeNoticeDeclineResponse;
import com.sairo.be.domain.billing.dto.response.PriceChangeNoticeItemResponse;
import com.sairo.be.domain.billing.entity.PriceChangeNotice;
import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.mapper.AgreementNoticeMapper;
import com.sairo.be.domain.billing.repository.BillingQueryRepository;
import com.sairo.be.domain.billing.repository.PriceChangeNoticeRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class PriceChangeNoticeService {

  private static final Sort NEWEST_FIRST =
      Sort.by(Sort.Order.desc("notifiedAt"), Sort.Order.desc("id"));

  private final PriceChangeNoticeRepository noticeRepository;
  private final BillingQueryRepository queryRepository;
  private final BillingContextService context;
  private final AdvisoryLock advisoryLock;
  private final Clock clock;

  public PriceChangeNoticeService(
      PriceChangeNoticeRepository noticeRepository,
      BillingQueryRepository queryRepository,
      BillingContextService context,
      AdvisoryLock advisoryLock,
      Clock clock) {
    this.noticeRepository = noticeRepository;
    this.queryRepository = queryRepository;
    this.context = context;
    this.advisoryLock = advisoryLock;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageResponse<PriceChangeNoticeItemResponse> list(
      StaffPrincipal principal, PageQuery page) {
    Page<PriceChangeNotice> found =
        noticeRepository.findByOfficeId(principal.officeId(), page.toPageable(NEWEST_FIRST));
    Set<Long> scheduleIds = new HashSet<>();
    found.forEach(
        notice -> {
          scheduleIds.add(notice.getFromPriceScheduleId());
          scheduleIds.add(notice.getToPriceScheduleId());
        });
    Map<Long, String> versions = queryRepository.findPriceVersions(scheduleIds);
    return PageResponse.from(
        found,
        notice ->
            AgreementNoticeMapper.toNoticeItem(
                notice,
                versions.get(notice.getFromPriceScheduleId()),
                versions.get(notice.getToPriceScheduleId())));
  }

  @Transactional
  public PriceChangeNoticeDeclineResponse decline(StaffPrincipal principal, Long noticeId) {
    Subscription subscription = context.requireSubscription(principal.officeId());
    advisoryLock.lockForTransaction("billing:subscription:" + subscription.getId());
    PriceChangeNotice notice =
        noticeRepository
            .findByIdAndOfficeId(noticeId, principal.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    Instant now = clock.instant();
    notice.decline(principal.membershipId(), now);
    noticeRepository.saveAndFlush(notice);
    subscription.markEventRecorded(now);
    return AgreementNoticeMapper.toDecline(notice);
  }
}
