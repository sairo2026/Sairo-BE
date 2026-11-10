package com.sairo.be.domain.legal.service;

import com.sairo.be.domain.legal.entity.LegalRetentionRule;
import com.sairo.be.domain.legal.repository.LegalRetentionRuleRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
@Transactional(readOnly = true)
public class WithdrawalRetentionService {

  private record Catalog(
      String ruleCode, String itemCode, String itemName, String reason, List<String> laws) {}

  private static final List<Catalog> CATALOG =
      List.of(
          new Catalog(
              "LOGIN_HISTORY", "ACCESS_LOG", "법정 접속·권한 기록", "접속 기록 보관", List.of("개인정보 보호법")),
          new Catalog(
              "TERMS_AGREEMENT_AFTER_WITHDRAWAL",
              "TERMS_AGREEMENT",
              "약관 동의·철회 이력",
              "분쟁 대응",
              List.of("개인정보 보호법", "전자상거래 관련 법령")),
          new Catalog(
              "BILLING_RECORD",
              "BILLING_RECORD",
              "결제·청구 기록",
              "결제·청구 기록 보관",
              List.of("국세기본법", "전자상거래 관련 법령")),
          new Catalog(
              "PRIVACY_REQUEST_RECORD",
              "PRIVACY_REQUEST_RECORD",
              "권리 요청 처리 이력",
              "권리 요청 처리 이력 보관",
              List.of("개인정보 보호법")));

  private final LegalRetentionRuleRepository ruleRepository;

  public WithdrawalRetentionService(LegalRetentionRuleRepository ruleRepository) {
    this.ruleRepository = ruleRepository;
  }

  public List<RetainedItemView> retainedItems() {
    Map<String, LegalRetentionRule> rules =
        ruleRepository.findByRuleCodeIn(CATALOG.stream().map(Catalog::ruleCode).toList()).stream()
            .collect(Collectors.toMap(LegalRetentionRule::getRuleCode, Function.identity()));
    List<RetainedItemView> items = new ArrayList<>();
    for (Catalog entry : CATALOG) {
      LegalRetentionRule rule = rules.get(entry.ruleCode());
      if (rule == null) {
        continue;
      }
      items.add(
          new RetainedItemView(
              entry.itemCode(),
              entry.itemName(),
              entry.reason(),
              entry.laws(),
              rule.getRetentionDays(),
              rule.getAnchorEvent(),
              rule.getStatus()));
    }
    return items;
  }
}
