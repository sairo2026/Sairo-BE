package com.sairo.be.domain.coordination.service;

import com.sairo.be.domain.coordination.entity.CustomerResponseLink;
import com.sairo.be.domain.coordination.repository.CustomerResponseLinkRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class PublicLinkIssuer {

  private final CustomerResponseLinkRepository linkRepository;
  private final PublicLinkTokenGenerator tokenGenerator;
  private final String frontendBaseUrl;

  public PublicLinkIssuer(
      CustomerResponseLinkRepository linkRepository,
      PublicLinkTokenGenerator tokenGenerator,
      @Value("${app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl) {
    this.linkRepository = linkRepository;
    this.tokenGenerator = tokenGenerator;
    this.frontendBaseUrl = frontendBaseUrl;
  }

  public IssuedLink issue(Long responseId) {
    Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    String provisionalHash = tokenGenerator.hash(UUID.randomUUID().toString());
    CustomerResponseLink link =
        CustomerResponseLink.issueWithProvisionalHash(responseId, provisionalHash, issuedAt);
    linkRepository.save(link);

    String token = tokenGenerator.generate(link.getId(), link.getIssuedAt());
    link.assignTokenHash(tokenGenerator.hash(token));

    return new IssuedLink(link, frontendBaseUrl + "/visit-responses/" + token);
  }

  public String reconstructUrl(CustomerResponseLink link) {
    String token = tokenGenerator.generate(link.getId(), link.getIssuedAt());
    return frontendBaseUrl + "/visit-responses/" + token;
  }

  public record IssuedLink(CustomerResponseLink link, String customerLinkUrl) {}
}
