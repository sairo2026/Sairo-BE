package com.sairo.be.domain.contract.entity;

/** 요청으로 받은 당사자 한 명이다. partyId와 sequenceNo는 정정 요청에서만 쓴다. */
public record PartyInput(
    Long partyId, Integer sequenceNo, String name, String phone, Long customerId) {}
