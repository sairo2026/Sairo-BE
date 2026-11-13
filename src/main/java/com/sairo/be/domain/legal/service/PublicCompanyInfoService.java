package com.sairo.be.domain.legal.service;

import com.sairo.be.domain.legal.dto.response.PublicCompanyInfoResponse;
import com.sairo.be.domain.legal.dto.response.PublicCompanyInfoResponse.PolicyLink;
import com.sairo.be.domain.legal.entity.PolicyDocument;
import com.sairo.be.domain.legal.mapper.CompanyInfoMapper;
import com.sairo.be.domain.legal.repository.CompanyServiceInfoRepository;
import com.sairo.be.domain.legal.repository.PolicyDocumentRepository;
import com.sairo.be.global.config.CompanyProperties;
import com.sairo.be.global.support.RequestRateLimiter;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class PublicCompanyInfoService {

  static final String DEFAULT_POLICY_VERSION = "1.0";

  private record LinkKind(String kind, String title) {}

  private static final List<LinkKind> LINK_KINDS =
      List.of(
          new LinkKind("TERMS", "이용약관"),
          new LinkKind("PRIVACY", "개인정보처리방침"),
          new LinkKind("REFUND", "환불정책"));

  private final CompanyServiceInfoRepository infoRepository;
  private final PolicyDocumentRepository policyRepository;
  private final CompanyProperties properties;
  private final RequestRateLimiter rateLimiter;
  private final Clock clock;

  public PublicCompanyInfoService(
      CompanyServiceInfoRepository infoRepository,
      PolicyDocumentRepository policyRepository,
      CompanyProperties properties,
      RequestRateLimiter rateLimiter,
      Clock clock) {
    this.infoRepository = infoRepository;
    this.policyRepository = policyRepository;
    this.properties = properties;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PublicCompanyInfoResponse current(String clientIp) {
    rateLimiter.hit(LegalRateLimits.PUBLIC_COMPANY_INFO_IP, clientIp);
    Instant now = clock.instant();
    List<PolicyLink> links = policyLinks(now);
    return infoRepository
        .findLatestPublished(now)
        .map(info -> CompanyInfoMapper.fromPublished(info, links))
        .orElseGet(() -> CompanyInfoMapper.fromConfiguration(properties, links));
  }

  private List<PolicyLink> policyLinks(Instant now) {
    Map<String, String> versions = new HashMap<>();
    List<String> kinds = LINK_KINDS.stream().map(LinkKind::kind).toList();
    for (PolicyDocument document : policyRepository.findPublishedInForce(kinds, now)) {
      versions.putIfAbsent(document.getDocType(), document.getVersion());
    }
    return LINK_KINDS.stream()
        .map(
            link ->
                new PolicyLink(
                    link.kind(),
                    link.title(),
                    "/legal/policies/"
                        + link.kind()
                        + "/"
                        + versions.getOrDefault(link.kind(), DEFAULT_POLICY_VERSION)))
        .toList();
  }
}
