package com.sairo.be.domain.office.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "고용명부 목록과 중개보조원 한도")
public record EmploymentRosterListResponse(
    Quota quota, List<EmploymentRosterItemResponse> items, int page, int size, long totalElements) {

  public record Quota(long qualifiedCount, long deputyCount, long deputyLimit, boolean exceeded) {}
}
