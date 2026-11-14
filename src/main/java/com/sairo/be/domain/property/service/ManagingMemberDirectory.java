package com.sairo.be.domain.property.service;

import java.util.Optional;

public interface ManagingMemberDirectory {

  record ManagingMember(
      Long membershipId, Long officeId, String role, String status, String displayName) {

    public boolean canManageProperty() {
      return "APPROVED".equals(status) && ("OWNER".equals(role) || "AGENT".equals(role));
    }
  }

  Optional<ManagingMember> find(Long membershipId);
}
