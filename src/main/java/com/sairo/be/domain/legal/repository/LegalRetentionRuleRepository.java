package com.sairo.be.domain.legal.repository;

import com.sairo.be.domain.legal.entity.LegalRetentionRule;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LegalRetentionRuleRepository extends JpaRepository<LegalRetentionRule, Long> {

  List<LegalRetentionRule> findByRuleCodeIn(Collection<String> ruleCodes);
}
