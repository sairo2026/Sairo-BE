package com.sairo.be.global.security;

import java.time.Instant;

public record VerifiedTicket(
    TicketType type,
    String subject,
    String purpose,
    Long userId,
    String ticketId,
    Instant expiresAt) {}
