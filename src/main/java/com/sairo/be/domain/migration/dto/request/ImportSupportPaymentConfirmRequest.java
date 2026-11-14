package com.sairo.be.domain.migration.dto.request;

public record ImportSupportPaymentConfirmRequest(String paymentKey, String orderId, long amount) {}
