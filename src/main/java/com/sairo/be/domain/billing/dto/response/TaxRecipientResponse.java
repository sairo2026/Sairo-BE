package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "세금계산서 수신 정보. 저장된 값이 없으면 사무소 정보 기반 초기값 제안이다.")
public record TaxRecipientResponse(
    boolean registered,
    Long recipientId,
    String businessNumber,
    String companyName,
    String representativeName,
    String receiverEmail,
    String taxType,
    boolean issueRequested,
    OffsetDateTime registeredAt) {}
