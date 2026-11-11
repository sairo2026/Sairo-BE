package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.visit.repository.VisitCustomerLinkRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.support.PublicLinkTokenGenerator;
import com.sairo.be.global.support.RequestRateLimiter;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class PublicLinkResolver {

  static final int TOKEN_MAX_LENGTH = 512;

  private final VisitCustomerLinkRepository linkRepository;
  private final PublicLinkTokenGenerator tokenGenerator;
  private final RequestRateLimiter rateLimiter;

  public PublicLinkResolver(
      VisitCustomerLinkRepository linkRepository,
      PublicLinkTokenGenerator tokenGenerator,
      RequestRateLimiter rateLimiter) {
    this.linkRepository = linkRepository;
    this.tokenGenerator = tokenGenerator;
    this.rateLimiter = rateLimiter;
  }

  public VisitCustomerLinkRepository.LinkHead resolve(String token, String clientIp) {
    VisitCustomerLinkRepository.LinkHead link = resolveIncludingRevoked(token, clientIp);
    if (link.getRevokedAt() != null) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
    return link;
  }

  /** 확정 조회는 폐기된 토큰에도 재확인 안내를 돌려줘야 하므로 폐기 여부를 호출한 쪽이 판단한다. */
  public VisitCustomerLinkRepository.LinkHead resolveIncludingRevoked(
      String token, String clientIp) {
    rateLimiter.hit(VisitRateLimits.PUBLIC_LINK_IP, clientIp);
    if (token == null || token.isBlank() || token.length() > TOKEN_MAX_LENGTH) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
    String hash = tokenGenerator.hash(token);
    rateLimiter.hit(VisitRateLimits.PUBLIC_LINK_TOKEN, hash);
    VisitCustomerLinkRepository.LinkHead link =
        linkRepository
            .findHeadByTokenHash(hash)
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    return link;
  }
}
