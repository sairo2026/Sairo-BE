package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.entity.VisitCustomerLink;
import com.sairo.be.domain.visit.repository.VisitCoordinationRepository;
import com.sairo.be.domain.visit.repository.VisitCustomerLinkRepository;
import com.sairo.be.domain.visit.repository.VisitCustomerTimeSlotRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two independent, unexpired link tokens prove access to both requests before time data is reused.
 */
@Profile("!migrate")
@Service
public class PublicCustomerTimeReuseService {

  public record TimeSlot(OffsetDateTime startsAt, OffsetDateTime endsAt) {}

  public record ReusableTimes(List<TimeSlot> timeSlots) {}

  private final PublicLinkResolver resolver;
  private final VisitCustomerLinkRepository links;
  private final VisitCoordinationRepository coordinations;
  private final VisitCustomerTimeSlotRepository slots;
  private final Clock clock;

  public PublicCustomerTimeReuseService(
      PublicLinkResolver resolver,
      VisitCustomerLinkRepository links,
      VisitCoordinationRepository coordinations,
      VisitCustomerTimeSlotRepository slots,
      Clock clock) {
    this.resolver = resolver;
    this.links = links;
    this.coordinations = coordinations;
    this.slots = slots;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public ReusableTimes preview(String currentToken, String previousToken, String clientIp) {
    VisitCustomerLink current = active(currentToken, clientIp);
    return previewForCurrent(current, previousToken, clientIp);
  }

  /**
   * A prior response in this browser is a second proof; the new link alone never reveals history.
   */
  @Transactional(readOnly = true)
  public ReusableTimes previewFromBrowser(
      String currentToken, String previousToken, String clientIp) {
    VisitCustomerLink current = active(currentToken, clientIp);
    if (previousToken == null || previousToken.isBlank()) {
      return new ReusableTimes(List.of());
    }
    try {
      return previewForCurrent(current, previousToken, clientIp);
    } catch (BusinessException error) {
      if (error.getErrorCode() == ErrorCode.PUBLIC_LINK_NOT_FOUND
          || error.getErrorCode() == ErrorCode.PUBLIC_LINK_EXPIRED) {
        return new ReusableTimes(List.of());
      }
      throw error;
    }
  }

  private ReusableTimes previewForCurrent(
      VisitCustomerLink current, String previousToken, String clientIp) {
    VisitCustomerLink previous = active(previousToken, clientIp);
    VisitCoordination target = coordination(current);
    VisitCoordination source = coordination(previous);
    if (target.getId().equals(source.getId())
        || !target.getOfficeId().equals(source.getOfficeId())
        || !target.getCustomerId().equals(source.getCustomerId())) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
    if (!target.getStatus().acceptsCustomerResponse()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant now = clock.instant();
    Instant start = TimeRules.startOfDisplayDay(target.getPeriodStartDate());
    Instant end = TimeRules.endOfDisplayDayExclusive(target.getPeriodEndDate());
    if (previous.getResponseVersion() == 0) {
      return new ReusableTimes(List.of());
    }
    List<TimeSlot> reusable =
        slots
            .findByCustomerLinkIdAndResponseVersionOrderByStartsAtAscIdAsc(
                previous.getId(), previous.getResponseVersion())
            .stream()
            .filter(slot -> slot.getStartsAt().isAfter(now))
            .filter(slot -> !slot.getStartsAt().isBefore(start) && !slot.getEndsAt().isAfter(end))
            .map(
                slot ->
                    new TimeSlot(
                        TimeRules.toDisplayOffset(slot.getStartsAt()),
                        TimeRules.toDisplayOffset(slot.getEndsAt())))
            .toList();
    return new ReusableTimes(reusable);
  }

  private VisitCustomerLink active(String token, String clientIp) {
    var head = resolver.resolve(token, clientIp);
    if (!clock.instant().isBefore(head.getExpiresAt())) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_EXPIRED);
    }
    return links
        .findById(head.getId())
        .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
  }

  private VisitCoordination coordination(VisitCustomerLink link) {
    return coordinations
        .findById(link.getCoordinationId())
        .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
  }
}
