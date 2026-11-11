package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.visit.dto.response.VisitRequestNoticeListResponse;
import com.sairo.be.domain.visit.entity.VisitRequest;
import com.sairo.be.domain.visit.entity.VisitRequestNotice;
import com.sairo.be.domain.visit.mapper.VisitRequestMapper;
import com.sairo.be.domain.visit.repository.VisitRequestNoticeRepository;
import com.sairo.be.domain.visit.repository.VisitRequestRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class VisitRequestNoticeService {

  private static final int LIST_LIMIT = 200;

  private final VisitRequestNoticeRepository noticeRepository;
  private final VisitRequestRepository requestRepository;
  private final Clock clock;

  public VisitRequestNoticeService(
      VisitRequestNoticeRepository noticeRepository,
      VisitRequestRepository requestRepository,
      Clock clock) {
    this.noticeRepository = noticeRepository;
    this.requestRepository = requestRepository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public VisitRequestNoticeListResponse list(StaffPrincipal principal, boolean unreadOnly) {
    PageRequest page = PageRequest.of(0, LIST_LIMIT);
    List<VisitRequestNotice> notices =
        unreadOnly
            ? noticeRepository.findByRecipientMembershipIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(
                principal.membershipId(), page)
            : noticeRepository.findByRecipientMembershipIdOrderByCreatedAtDescIdDesc(
                principal.membershipId(), page);
    Map<Long, Long> coordinations = new HashMap<>();
    for (VisitRequest request :
        requestRepository.findAllById(
            notices.stream().map(VisitRequestNotice::getRequestId).distinct().toList())) {
      coordinations.put(request.getId(), request.getCoordinationId());
    }
    return VisitRequestMapper.toNotices(notices, coordinations);
  }

  @Transactional
  public void markRead(StaffPrincipal principal, Long noticeId) {
    VisitRequestNotice notice =
        noticeRepository
            .findByIdAndRecipientMembershipId(noticeId, principal.membershipId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    notice.markRead(clock.instant());
  }
}
