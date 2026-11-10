package com.sairo.be.domain.legal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "legal_retention_rule")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalRetentionRule {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "rule_code", nullable = false, length = 50)
  private String ruleCode;

  @Column(name = "retention_days", nullable = false)
  private int retentionDays;

  @Column(name = "anchor_event", nullable = false, length = 20)
  private String anchorEvent;

  @Column(nullable = false, length = 20)
  private String status;
}
