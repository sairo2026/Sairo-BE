package com.sairo.be.domain.coordination.mapper;

import com.sairo.be.domain.coordination.dto.response.CoordinationCreateResponse;
import com.sairo.be.domain.coordination.entity.Coordination;
import com.sairo.be.domain.coordination.entity.CoordinationCustomerResponse;
import com.sairo.be.domain.coordination.entity.CustomerResponseLink;

public final class CoordinationMapper {

  private CoordinationMapper() {}

  public static CoordinationCreateResponse toCreateResponse(
      Coordination coordination,
      CoordinationCustomerResponse tenantResponse,
      CustomerResponseLink link,
      String customerLinkUrl) {
    return new CoordinationCreateResponse(
        coordination.getId(),
        coordination.getStatus(),
        tenantResponse.getId(),
        customerLinkUrl,
        link.getExpiresAt());
  }
}
