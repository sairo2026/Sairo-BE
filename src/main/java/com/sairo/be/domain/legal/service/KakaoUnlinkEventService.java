package com.sairo.be.domain.legal.service;

import com.sairo.be.domain.legal.entity.KakaoUnlinkDirection;
import com.sairo.be.domain.legal.entity.KakaoUnlinkStatus;
import com.sairo.be.domain.legal.entity.LegalKakaoUnlinkEvent;
import com.sairo.be.domain.legal.repository.LegalKakaoUnlinkEventRepository;
import com.sairo.be.global.security.SecurityProperties;
import com.sairo.be.global.support.HmacSupport;
import com.sairo.be.global.support.TextCipher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class KakaoUnlinkEventService {

  public static final Duration DUPLICATE_WINDOW = Duration.ofMinutes(10);

  private final LegalKakaoUnlinkEventRepository repository;
  private final TextCipher textCipher;
  private final SecurityProperties properties;
  private final Clock clock;

  public KakaoUnlinkEventService(
      LegalKakaoUnlinkEventRepository repository,
      TextCipher textCipher,
      SecurityProperties properties,
      Clock clock) {
    this.repository = repository;
    this.textCipher = textCipher;
    this.properties = properties;
    this.clock = clock;
  }

  public String keyHmac(String kakaoKey) {
    return HmacSupport.hmacHex(properties.kakaoKeyHmacSecret(), kakaoKey);
  }

  @Transactional
  public void enqueueOutbound(Long userId, String kakaoKey) {
    repository.save(
        LegalKakaoUnlinkEvent.outbound(
            userId,
            keyHmac(kakaoKey),
            (short) properties.kakaoKeyHmacVersion(),
            textCipher.encrypt(kakaoKey),
            clock.instant()));
  }

  @Transactional
  public boolean isDuplicateInbound(String kakaoKey, String referrerType) {
    Instant since = clock.instant().minus(DUPLICATE_WINDOW);
    return repository.existsRecent(
        KakaoUnlinkDirection.INBOUND_WEBHOOK, keyHmac(kakaoKey), referrerType, since);
  }

  @Transactional
  public void recordInbound(
      Long userId, String kakaoKey, String referrerType, KakaoUnlinkStatus status) {
    repository.save(
        LegalKakaoUnlinkEvent.inbound(
            userId,
            keyHmac(kakaoKey),
            (short) properties.kakaoKeyHmacVersion(),
            referrerType,
            status,
            clock.instant()));
  }
}
