package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.legal.service.RetentionRuleLookup;
import com.sairo.be.domain.legal.service.TermsCatalogService;
import com.sairo.be.domain.legal.service.TermsVersionView;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class ApproverLinkNoticeProvider {

  public static final String NOTICE_CODE = "GUEST_LINK_NOTICE_APPROVER";

  public record Notice(String noticeCode, String noticeVersion, int retentionDays) {}

  private final TermsCatalogService termsCatalogService;
  private final RetentionRuleLookup retentionRules;

  public ApproverLinkNoticeProvider(
      TermsCatalogService termsCatalogService, RetentionRuleLookup retentionRules) {
    this.termsCatalogService = termsCatalogService;
    this.retentionRules = retentionRules;
  }

  public Notice current() {
    TermsVersionView terms =
        termsCatalogService
            .findInForceByCode(NOTICE_CODE)
            .orElseThrow(() -> new IllegalStateException("시행 중인 승인자 링크 개인정보 안내가 없습니다."));
    int retentionDays =
        retentionRules
            .retentionDays(PropertyAccessApprovalService.RETENTION_RULE)
            .orElseThrow(() -> new IllegalStateException("승인자 개인정보 보관 규칙이 없습니다."));
    return new Notice(NOTICE_CODE, terms.version(), retentionDays);
  }
}
