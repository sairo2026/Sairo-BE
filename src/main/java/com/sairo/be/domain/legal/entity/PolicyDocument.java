package com.sairo.be.domain.legal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "policy_document")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PolicyDocument {

  @Id private Long id;

  @Column(name = "doc_type", nullable = false, length = 24)
  private String docType;

  @Column(nullable = false, length = 20)
  private String version;

  @Column(nullable = false, length = 12)
  private String status;

  @Column(name = "effective_at")
  private Instant effectiveAt;
}
