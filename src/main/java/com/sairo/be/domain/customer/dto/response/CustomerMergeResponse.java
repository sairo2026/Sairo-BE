package com.sairo.be.domain.customer.dto.response;

import java.time.OffsetDateTime;

public record CustomerMergeResponse(
    Long primaryCustomerId, Long mergedCustomerId, OffsetDateTime mergedAt, Long version) {}
