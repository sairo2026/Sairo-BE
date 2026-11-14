package com.sairo.be.global.port;

public interface AssistantAssignments {

  boolean isAssignedCustomer(Long officeId, Long membershipId, Long customerId);
}
