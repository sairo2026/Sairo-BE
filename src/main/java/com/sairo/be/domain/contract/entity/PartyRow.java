package com.sairo.be.domain.contract.entity;

public record PartyRow(
    Long id, PartySide side, int sequenceNo, String name, String phone, Long customerId) {}
