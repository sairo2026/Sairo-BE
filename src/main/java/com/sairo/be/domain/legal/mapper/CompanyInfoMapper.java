package com.sairo.be.domain.legal.mapper;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.legal.dto.response.PublicCompanyInfoResponse;
import com.sairo.be.domain.legal.dto.response.PublicCompanyInfoResponse.PolicyLink;
import com.sairo.be.domain.legal.entity.CompanyServiceInfo;
import com.sairo.be.global.config.CompanyProperties;
import java.util.List;

public final class CompanyInfoMapper {

  private CompanyInfoMapper() {}

  public static PublicCompanyInfoResponse fromPublished(
      CompanyServiceInfo info, List<PolicyLink> links) {
    return new PublicCompanyInfoResponse(
        info.getVersionNo(),
        toDisplayOffset(info.getPublishedAt()),
        info.getBusinessName(),
        info.getRepresentativeName(),
        info.getBusinessNumber(),
        info.getAddress(),
        blankToNull(info.getContactPhone()),
        blankToNull(info.getEcommerceNotice()),
        blankToNull(info.getSupportEmail()),
        blankToNull(info.getPrivacyContact()),
        blankToNull(info.getPrivacyRequestPath()),
        links);
  }

  public static PublicCompanyInfoResponse fromConfiguration(
      CompanyProperties properties, List<PolicyLink> links) {
    return new PublicCompanyInfoResponse(
        null,
        null,
        properties.businessName(),
        properties.representativeName(),
        properties.businessNumber(),
        properties.address(),
        blankToNull(properties.contactPhone()),
        blankToNull(properties.ecommerceNotice()),
        blankToNull(properties.supportEmail()),
        blankToNull(properties.privacyContact()),
        blankToNull(properties.privacyRequestPath()),
        links);
  }

  public static boolean isComplete(CompanyProperties properties) {
    return !isBlank(properties.businessName())
        && !isBlank(properties.representativeName())
        && !isBlank(properties.businessNumber())
        && !isBlank(properties.address());
  }

  private static String blankToNull(String value) {
    return isBlank(value) ? null : value.strip();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
