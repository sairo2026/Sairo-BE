package com.sairo.be.global.security;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.support.HmacSupport;
import com.sairo.be.global.support.TokenGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class SignedTicketService {

  private static final String USED_KEY_PREFIX = "sairo:ticket-used:";

  private final SecurityProperties properties;
  private final StringRedisTemplate redis;
  private final TokenGenerator tokenGenerator;
  private final Clock clock;

  public SignedTicketService(
      SecurityProperties properties,
      StringRedisTemplate redis,
      TokenGenerator tokenGenerator,
      Clock clock) {
    this.properties = properties;
    this.redis = redis;
    this.tokenGenerator = tokenGenerator;
    this.clock = clock;
  }

  public IssuedTicket issue(
      TicketType type, String subject, String purpose, Long userId, Duration ttl) {
    Instant expiresAt = clock.instant().plus(ttl);
    String body =
        String.join(
            ".",
            encode(type.name()),
            encode(subject),
            encode(purpose == null ? "" : purpose),
            encode(userId == null ? "" : String.valueOf(userId)),
            encode(tokenGenerator.randomToken(16)),
            encode(String.valueOf(expiresAt.getEpochSecond())));
    String token = body + "~" + HmacSupport.hmacBase64Url(properties.ticketSecret(), body);
    return new IssuedTicket(token, expiresAt);
  }

  public VerifiedTicket verify(String token, TicketType expectedType, String expectedPurpose) {
    if (token == null) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
    int separator = token.indexOf('~');
    if (separator <= 0 || separator == token.length() - 1) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
    String body = token.substring(0, separator);
    String signature = token.substring(separator + 1);
    String expected = HmacSupport.hmacBase64Url(properties.ticketSecret(), body);
    if (!HmacSupport.constantTimeEquals(expected, signature)) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
    String[] parts = body.split("\\.", -1);
    if (parts.length != 6) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
    try {
      TicketType type = TicketType.valueOf(decode(parts[0]));
      String subject = decode(parts[1]);
      String purpose = decode(parts[2]);
      String userIdText = decode(parts[3]);
      String ticketId = decode(parts[4]);
      Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(decode(parts[5])));
      if (type != expectedType || (expectedPurpose != null && !expectedPurpose.equals(purpose))) {
        throw new BusinessException(ErrorCode.TOKEN_INVALID);
      }
      if (!expiresAt.isAfter(clock.instant())) {
        throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
      }
      if (Boolean.TRUE.equals(redis.hasKey(USED_KEY_PREFIX + ticketId))) {
        throw new BusinessException(ErrorCode.TOKEN_INVALID);
      }
      Long userId = userIdText.isEmpty() ? null : Long.valueOf(userIdText);
      return new VerifiedTicket(type, subject, purpose, userId, ticketId, expiresAt);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
  }

  public void consume(VerifiedTicket ticket) {
    Duration remaining = Duration.between(clock.instant(), ticket.expiresAt());
    if (remaining.isNegative() || remaining.isZero()) {
      throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
    }
    Boolean stored =
        redis
            .opsForValue()
            .setIfAbsent(USED_KEY_PREFIX + ticket.ticketId(), "1", remaining.plusMinutes(1));
    if (!Boolean.TRUE.equals(stored)) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
  }

  private static String encode(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String decode(String value) {
    return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
  }

  public record IssuedTicket(String token, Instant expiresAt) {}
}
