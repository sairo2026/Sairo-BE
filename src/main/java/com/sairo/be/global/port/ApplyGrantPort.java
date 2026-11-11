package com.sairo.be.global.port;

import java.util.Optional;

public interface ApplyGrantPort {

  Optional<Long> findActiveGrantId(
      Long granteeOfficeId, Long granteeMembershipId, Long approvalId, Long propertyId);
}
