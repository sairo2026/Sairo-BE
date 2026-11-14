package com.sairo.be.domain.contract.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "계약 공동당사자 한 명이다.")
public record PartyResponse(
    Long partyId,
    int sequenceNo,
    String name,
    @Schema(nullable = true) String phone,
    @Schema(nullable = true) Long customerId) {}
