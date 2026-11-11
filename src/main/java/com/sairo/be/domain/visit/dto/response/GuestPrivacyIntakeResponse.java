package com.sairo.be.domain.visit.dto.response;

import java.time.OffsetDateTime;

public record GuestPrivacyIntakeResponse(
    OffsetDateTime receivedAt,
    OffsetDateTime resultDueAt,
    String contactChannel,
    String maskedContact) {}
