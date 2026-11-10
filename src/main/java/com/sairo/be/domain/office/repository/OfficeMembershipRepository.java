package com.sairo.be.domain.office.repository;

import com.sairo.be.domain.office.entity.OfficeMembership;
import com.sairo.be.domain.office.entity.OfficeMembershipStatus;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OfficeMembershipRepository extends JpaRepository<OfficeMembership, Long> {

  Optional<OfficeMembership> findByUserIdAndStatus(Long userId, OfficeMembershipStatus status);

  boolean existsByUserIdAndStatusIn(Long userId, Collection<OfficeMembershipStatus> statuses);
}
