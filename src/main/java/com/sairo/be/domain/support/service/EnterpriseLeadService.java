package com.sairo.be.domain.support.service;

import com.sairo.be.domain.support.dto.response.EnterpriseLeadCreateResponse;
import com.sairo.be.domain.support.dto.response.EnterpriseLeadStatusResponse;
import com.sairo.be.domain.support.dto.response.EnterpriseQuoteDecisionResponse;
import com.sairo.be.domain.support.entity.EnterpriseLead;
import com.sairo.be.domain.support.entity.EnterpriseLeadStatusHistory;
import com.sairo.be.domain.support.entity.EnterpriseQuote;
import com.sairo.be.domain.support.entity.LeadStatus;
import com.sairo.be.domain.support.mapper.EnterpriseLeadMapper;
import com.sairo.be.domain.support.repository.EnterpriseLeadRepository;
import com.sairo.be.domain.support.repository.EnterpriseLeadStatusHistoryRepository;
import com.sairo.be.domain.support.repository.EnterpriseQuoteRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.support.HmacSupport;
import com.sairo.be.global.support.RequestRateLimiter;
import com.sairo.be.global.support.TokenGenerator;
import java.time.Clock;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class EnterpriseLeadService {

  static final int TRACKING_KEY_BYTES = 32;
  static final int TRACKING_KEY_MAX_LENGTH = 200;

  private final EnterpriseLeadRepository leadRepository;
  private final EnterpriseQuoteRepository quoteRepository;
  private final EnterpriseLeadStatusHistoryRepository historyRepository;
  private final TokenGenerator tokenGenerator;
  private final RequestRateLimiter rateLimiter;
  private final Clock clock;

  public EnterpriseLeadService(
      EnterpriseLeadRepository leadRepository,
      EnterpriseQuoteRepository quoteRepository,
      EnterpriseLeadStatusHistoryRepository historyRepository,
      TokenGenerator tokenGenerator,
      RequestRateLimiter rateLimiter,
      Clock clock) {
    this.leadRepository = leadRepository;
    this.quoteRepository = quoteRepository;
    this.historyRepository = historyRepository;
    this.tokenGenerator = tokenGenerator;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  @Transactional
  public EnterpriseLeadCreateResponse create(JsonNode body, String clientIp) {
    rateLimiter.hit(SupportRateLimits.ENTERPRISE_LEAD_CREATE_IP, clientIp);
    EnterpriseLeadParser.LeadInput input = EnterpriseLeadParser.parse(body);
    String trackingKey = tokenGenerator.randomToken(TRACKING_KEY_BYTES);
    Instant now = clock.instant();
    EnterpriseLead lead =
        leadRepository.saveAndFlush(
            EnterpriseLead.receive(
                input.companyName(),
                input.contactName(),
                input.contactEmail(),
                input.contactPhone(),
                input.branchCount(),
                input.expectedSeats(),
                input.migrationNeeded(),
                input.content(),
                HmacSupport.sha256Hex(trackingKey),
                now));
    historyRepository.save(EnterpriseLeadStatusHistory.received(lead.getId(), now));
    return EnterpriseLeadMapper.toCreate(lead, trackingKey);
  }

  @Transactional(readOnly = true)
  public EnterpriseLeadStatusResponse status(Long leadId, String trackingKey, String clientIp) {
    throttle(leadId, clientIp);
    EnterpriseLead lead =
        leadRepository
            .findById(leadId)
            .filter(found -> matches(found, trackingKey))
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    EnterpriseQuote latest =
        quoteRepository.findFirstByLeadIdOrderByVersionNoDesc(leadId).orElse(null);
    return EnterpriseLeadMapper.toStatus(lead, latest);
  }

  @Transactional
  public EnterpriseQuoteDecisionResponse accept(
      Long leadId, Long quoteId, String trackingKey, JsonNode body, String clientIp) {
    EnterpriseLeadParser.requireEmptyBody(body);
    return decide(leadId, quoteId, trackingKey, clientIp, true);
  }

  @Transactional
  public EnterpriseQuoteDecisionResponse reject(
      Long leadId, Long quoteId, String trackingKey, JsonNode body, String clientIp) {
    EnterpriseLeadParser.requireEmptyBody(body);
    return decide(leadId, quoteId, trackingKey, clientIp, false);
  }

  private EnterpriseQuoteDecisionResponse decide(
      Long leadId, Long quoteId, String trackingKey, String clientIp, boolean accept) {
    throttle(leadId, clientIp);
    EnterpriseLead lead =
        leadRepository
            .findForUpdateById(leadId)
            .filter(found -> matches(found, trackingKey))
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    EnterpriseQuote quote =
        quoteRepository
            .findForUpdate(quoteId, leadId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    Instant now = clock.instant();
    requireDecidable(lead, quote, now);
    if (accept) {
      quote.accept(now);
      lead.customerAccepted(now);
      historyRepository.save(EnterpriseLeadStatusHistory.quoteAccepted(leadId, quoteId, now));
    } else {
      quote.reject(now);
      lead.customerRejected(now);
      historyRepository.save(EnterpriseLeadStatusHistory.quoteRejected(leadId, quoteId, now));
    }
    quoteRepository.saveAndFlush(quote);
    EnterpriseLead saved = leadRepository.saveAndFlush(lead);
    return EnterpriseLeadMapper.toDecision(saved, quote);
  }

  private void requireDecidable(EnterpriseLead lead, EnterpriseQuote quote, Instant now) {
    EnterpriseQuote latest =
        quoteRepository.findFirstByLeadIdOrderByVersionNoDesc(lead.getId()).orElse(null);
    if (quote.isSuperseded() || latest == null || !latest.getId().equals(quote.getId())) {
      throw new BusinessException(ErrorCode.QUOTE_SUPERSEDED);
    }
    if (lead.getStatus() != LeadStatus.QUOTED || quote.hasResponse()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (quote.isExpiredAt(now)) {
      throw new BusinessException(ErrorCode.QUOTE_EXPIRED);
    }
  }

  private void throttle(Long leadId, String clientIp) {
    rateLimiter.hit(SupportRateLimits.ENTERPRISE_LEAD_ACCESS_IP, clientIp);
    rateLimiter.hit(SupportRateLimits.ENTERPRISE_LEAD_ACCESS_LEAD, String.valueOf(leadId));
  }

  private boolean matches(EnterpriseLead lead, String trackingKey) {
    if (trackingKey == null
        || trackingKey.isBlank()
        || trackingKey.length() > TRACKING_KEY_MAX_LENGTH) {
      return false;
    }
    return HmacSupport.constantTimeEquals(
        lead.getTrackingKeyHash(), HmacSupport.sha256Hex(trackingKey));
  }
}
