package com.sairo.be.domain.coordination.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "coordination_candidate_time")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CoordinationCandidateTime {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "coordination_id", nullable = false)
  private Long coordinationId;

  @Column(name = "starts_at", nullable = false)
  private Instant startsAt;

  private CoordinationCandidateTime(Long coordinationId, Instant startsAt) {
    this.coordinationId = coordinationId;
    this.startsAt = startsAt;
  }

  public static CoordinationCandidateTime of(Long coordinationId, Instant startsAt) {
    return new CoordinationCandidateTime(coordinationId, startsAt);
  }
}
