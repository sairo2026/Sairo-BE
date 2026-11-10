package com.sairo.be.domain.office.service;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;

public record ApprovedMembership(Long membershipId, Long officeId, OfficeMembershipRole role) {}
