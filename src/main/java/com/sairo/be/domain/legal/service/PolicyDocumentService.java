package com.sairo.be.domain.legal.service;

import com.sairo.be.domain.legal.dto.response.PolicyDocumentListResponse;
import com.sairo.be.domain.legal.entity.PolicyDocument;
import com.sairo.be.domain.legal.repository.PolicyDocumentRepository;
import com.sairo.be.global.support.RequestRateLimiter;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class PolicyDocumentService {

  static final List<String> DOC_TYPES =
      List.of(
          "TERMS",
          "PRIVACY",
          "PROCESSING_OUTSOURCING",
          "ARCHIVE",
          "REFUND",
          "SUBPROCESSORS",
          "PRICING");

  private final PolicyDocumentRepository policyRepository;
  private final RequestRateLimiter rateLimiter;
  private final Clock clock;

  public PolicyDocumentService(
      PolicyDocumentRepository policyRepository, RequestRateLimiter rateLimiter, Clock clock) {
    this.policyRepository = policyRepository;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PolicyDocumentListResponse inForce(String clientIp) {
    rateLimiter.hit(LegalRateLimits.POLICY_DOCUMENTS_IP, clientIp);
    Map<String, PolicyDocument> latest = new LinkedHashMap<>();
    for (PolicyDocument document :
        policyRepository.findPublishedInForce(DOC_TYPES, clock.instant())) {
      latest.putIfAbsent(document.getDocType(), document);
    }
    List<PolicyDocumentListResponse.Item> items =
        DOC_TYPES.stream()
            .map(latest::get)
            .filter(java.util.Objects::nonNull)
            .map(
                document ->
                    new PolicyDocumentListResponse.Item(
                        document.getDocType(),
                        document.getVersion(),
                        TimeRules.toDisplayOffset(document.getEffectiveAt()),
                        document.isRequiresConsent(),
                        document.getBody()))
            .toList();
    return new PolicyDocumentListResponse(items);
  }
}
