package com.sairo.be.domain.coordination.entity;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerResponseCandidateId implements Serializable {

  private Long responseId;
  private Long candidateTimeId;

  public CustomerResponseCandidateId(Long responseId, Long candidateTimeId) {
    this.responseId = responseId;
    this.candidateTimeId = candidateTimeId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CustomerResponseCandidateId that)) {
      return false;
    }
    return Objects.equals(responseId, that.responseId)
        && Objects.equals(candidateTimeId, that.candidateTimeId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(responseId, candidateTimeId);
  }
}
