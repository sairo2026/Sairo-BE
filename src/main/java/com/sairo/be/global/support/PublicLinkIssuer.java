package com.sairo.be.global.support;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class PublicLinkIssuer {

  private final PublicLinkTokenGenerator tokenGenerator;
  private final String frontendBaseUrl;

  public PublicLinkIssuer(
      PublicLinkTokenGenerator tokenGenerator,
      @Value("${app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl) {
    this.tokenGenerator = tokenGenerator;
    this.frontendBaseUrl = frontendBaseUrl;
  }

  public IssuedLink issue(Long linkId, Instant issuedAt, String pathPrefix) {
    String token = tokenGenerator.generate(linkId, issuedAt);
    return new IssuedLink(token, tokenGenerator.hash(token), frontendBaseUrl + pathPrefix + token);
  }

  public record IssuedLink(String token, String tokenHash, String url) {}
}
