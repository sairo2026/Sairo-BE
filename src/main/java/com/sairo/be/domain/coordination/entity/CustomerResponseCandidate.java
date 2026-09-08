package com.sairo.be.domain.coordination.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "customer_response_candidate")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerResponseCandidate {

  @EmbeddedId private CustomerResponseCandidateId id;

  @Column(name = "coordination_id", nullable = false)
  private Long coordinationId;

  @Column(name = "is_selected", nullable = false)
  private boolean selected;

  private CustomerResponseCandidate(
      CustomerResponseCandidateId id, Long coordinationId, boolean selected) {
    this.id = id;
    this.coordinationId = coordinationId;
    this.selected = selected;
  }

  public static CustomerResponseCandidate selected(
      Long responseId, Long coordinationId, Long candidateTimeId) {
    return new CustomerResponseCandidate(
        new CustomerResponseCandidateId(responseId, candidateTimeId), coordinationId, true);
  }

  public static CustomerResponseCandidate offered(
      Long responseId, Long coordinationId, Long candidateTimeId) {
    return new CustomerResponseCandidate(
        new CustomerResponseCandidateId(responseId, candidateTimeId), coordinationId, false);
  }

  public void markSelected() {
    this.selected = true;
  }

  public void markUnselected() {
    this.selected = false;
  }
}
