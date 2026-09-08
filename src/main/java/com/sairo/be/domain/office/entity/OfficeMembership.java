package com.sairo.be.domain.office.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "office_membership")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OfficeMembership {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "office_id", nullable = false)
  private Office office;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private OfficeMembershipRole role;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private OfficeMembershipStatus status;
}
