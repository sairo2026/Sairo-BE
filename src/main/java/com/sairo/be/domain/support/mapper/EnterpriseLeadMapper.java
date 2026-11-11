package com.sairo.be.domain.support.mapper;

import com.sairo.be.domain.support.dto.response.EnterpriseLeadCreateResponse;
import com.sairo.be.domain.support.dto.response.EnterpriseLeadStatusResponse;
import com.sairo.be.domain.support.dto.response.EnterpriseQuoteDecisionResponse;
import com.sairo.be.domain.support.entity.EnterpriseLead;
import com.sairo.be.domain.support.entity.EnterpriseQuote;
import com.sairo.be.global.support.TimeRules;

public final class EnterpriseLeadMapper {

  private EnterpriseLeadMapper() {}

  public static EnterpriseLeadCreateResponse toCreate(EnterpriseLead lead, String trackingKey) {
    return new EnterpriseLeadCreateResponse(
        lead.getId(),
        trackingKey,
        lead.getStatus().name(),
        TimeRules.toDisplayOffset(lead.getCreatedAt()),
        TimeRules.toDisplayOffset(lead.getUpdatedAt()),
        lead.getVersion());
  }

  public static EnterpriseLeadStatusResponse toStatus(EnterpriseLead lead, EnterpriseQuote quote) {
    return new EnterpriseLeadStatusResponse(
        lead.getId(),
        lead.getStatus().name(),
        quote == null ? null : quote.getId(),
        quote == null ? null : quote.getVersionNo(),
        quote == null ? null : quote.getAmount(),
        quote == null ? null : TimeRules.toDisplayOffset(quote.getExpiresAt()),
        TimeRules.toDisplayOffset(lead.getCreatedAt()),
        TimeRules.toDisplayOffset(lead.getUpdatedAt()),
        lead.getVersion());
  }

  public static EnterpriseQuoteDecisionResponse toDecision(
      EnterpriseLead lead, EnterpriseQuote quote) {
    return new EnterpriseQuoteDecisionResponse(
        lead.getId(),
        lead.getStatus().name(),
        quote.getId(),
        quote.getVersionNo(),
        TimeRules.toDisplayOffset(lead.getCreatedAt()),
        TimeRules.toDisplayOffset(lead.getUpdatedAt()),
        lead.getVersion());
  }
}
