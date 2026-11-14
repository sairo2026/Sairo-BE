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
              "LOGIN_HISTORY",
              "ACCESS_LOG",
              "로그인과 접속 기록",
              "계정 보안 사고와 부정 이용의 확인",
              List.of("개인정보 보호법", "통신비밀보호법")),
          new Catalog(
              "TERMS_AGREEMENT_AFTER_WITHDRAWAL",
              "TERMS_AGREEMENT",
              "약관 동의와 철회 이력",
              "동의 사실의 확인과 분쟁 대응",
              List.of("개인정보 보호법", "전자상거래법")),
          new Catalog(
              "MARKETING_NOTICE_DELIVERY",
              "MARKETING_NOTICE_DELIVERY",
              "수신 설정 처리 결과 통지와 재확인 발송 이력",
              "광고성 정보 수신 설정의 처리 사실 확인",
              List.of("정보통신망법")),
          new Catalog(
              "BILLING_RECORD",
              "BILLING_RECORD",
              "구독, 결제, 청구 기록",
              "대금 결제와 재화 공급에 관한 기록 보존",
              List.of("전자상거래법", "국세기본법")),
          new Catalog(
              "TAX_DOCUMENT",
              "TAX_DOCUMENT",
              "세금계산서와 현금영수증 발급 기록",
              "세금 증빙의 보존",
              List.of("부가가치세법", "국세기본법")),
          new Catalog(
              "PRIVACY_REQUEST_RECORD",
              "PRIVACY_REQUEST_RECORD",
              "개인정보 권리 요청과 처리 이력",
              "요청 처리 사실의 증명",
              List.of("개인정보 보호법")),
          new Catalog(
              "SUPPORT_INQUIRY", "SUPPORT_INQUIRY", "지원 문의", "분쟁과 불만 처리 기록", List.of("전자상거래법")),
          new Catalog(
              "OFFICE_CONTRACT_DOCUMENT",
              "OFFICE_CONTRACT_DOCUMENT",
              "사무소가 수탁 보관하는 거래계약서",
              "사무소의 법정 서류 보존 의무 이행 지원",
              List.of("공인중개사법")),
          new Catalog(
              "OFFICE_EXPLANATION_DOCUMENT",
              "OFFICE_EXPLANATION_DOCUMENT",
              "사무소가 수탁 보관하는 중개대상물 확인설명서",
              "사무소의 법정 서류 보존 의무 이행 지원",
              List.of("공인중개사법")));

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
