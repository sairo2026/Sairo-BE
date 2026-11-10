package com.sairo.be.domain.legal.service;

import com.sairo.be.domain.legal.entity.LegalRetentionRule;
import com.sairo.be.domain.legal.repository.LegalRetentionRuleRepository;
import java.util.List;
import java.util.OptionalInt;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
@Transactional(readOnly = true)
public class RetentionRuleLookup {

  private final LegalRetentionRuleRepository ruleRepository;

  public RetentionRuleLookup(LegalRetentionRuleRepository ruleRepository) {
    this.ruleRepository = ruleRepository;
  }

  public OptionalInt retentionDays(String ruleCode) {
    List<LegalRetentionRule> rules = ruleRepository.findByRuleCodeIn(List.of(ruleCode));
    return rules.isEmpty() ? OptionalInt.empty() : OptionalInt.of(rules.get(0).getRetentionDays());
  }
}
