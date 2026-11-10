package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.visit.repository.VisitApproverPropertyLinkRepository;
import com.sairo.be.domain.visit.repository.VisitApproverPropertyLinkRepository.LinkHead;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.support.PublicLinkTokenGenerator;
import com.sairo.be.global.support.RequestRateLimiter;
import java.time.Clock;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class PublicApproverLinkResolver {

  private final VisitApproverPropertyLinkRepository linkRepository;
  private final PublicLinkTokenGenerator tokenGenerator;
  private final RequestRateLimiter rateLimiter;
  private final Clock clock;

  public PublicApproverLinkResolver(
      VisitApproverPropertyLinkRepository linkRepository,
      PublicLinkTokenGenerator tokenGenerator,
      RequestRateLimiter rateLimiter,
      Clock clock) {
    this.linkRepository = linkRepository;
    this.tokenGenerator = tokenGenerator;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public LinkHead resolve(String token, String clientIp) {
    rateLimiter.hit(VisitRateLimits.PUBLIC_LINK_IP, clientIp);
    if (token == null || token.isBlank() || token.length() > PublicLinkResolver.TOKEN_MAX_LENGTH) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
    String hash = tokenGenerator.hash(token);
    rateLimiter.hit(VisitRateLimits.PUBLIC_LINK_TOKEN, hash);
    LinkHead link =
        linkRepository
            .findHeadByTokenHash(hash)
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    if (link.getRevokedAt() != null) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
    if (!clock.instant().isBefore(link.getExpiresAt())) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_EXPIRED);
    }
    return link;
  }
}
