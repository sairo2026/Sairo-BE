package com.sairo.be.domain.visit.dto.response;

public record CandidatePropertyAddResponse(
    Long candidatePropertyId,
    Long coordinationId,
    Long propertyId,
    int sortOrder,
    Long officeId,
    Long version) {}
