package com.sairo.be.global.support;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.security.StaffPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Profile("!migrate")
@Component
public class IdempotentRequests {

  public static final String HEADER = "Idempotency-Key";
  public static final Duration RETENTION = Duration.ofHours(24);

  private static final Pattern KEY_FORMAT = Pattern.compile("[\\x21-\\x7E]{1,100}");

  private final JdbcClient jdbcClient;
  private final AdvisoryLock advisoryLock;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public IdempotentRequests(
      JdbcClient jdbcClient, AdvisoryLock advisoryLock, ObjectMapper objectMapper, Clock clock) {
    this.jdbcClient = jdbcClient;
    this.advisoryLock = advisoryLock;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public <T> T execute(
      StaffPrincipal principal,
      String operation,
      String key,
      JsonNode body,
      Class<T> responseType,
      Supplier<T> action) {
    if (key == null || key.isBlank()) {
      return action.get();
    }
    if (!KEY_FORMAT.matcher(key).matches()) {
      throw BusinessException.withFieldErrors(
          ErrorCode.INVALID_REQUEST,
          List.of(
              new FieldErrorItem(
                  HEADER, FieldErrorCode.INVALID_FORMAT, "멱등 키는 공백 없는 1~100자의 영문·숫자·기호여야 합니다.")));
    }
    long membershipId = principal.membershipId();
    advisoryLock.lockForTransaction("idempotency:" + membershipId + ":" + operation + ":" + key);
    String hash = hash(body);
    Instant now = clock.instant();
    StoredRecord stored =
        jdbcClient
            .sql(
                "SELECT request_hash, response_body::text, created_at FROM idempotency_record"
                    + " WHERE membership_id = :membershipId AND operation = :operation"
                    + " AND idempotency_key = :key")
            .param("membershipId", membershipId)
            .param("operation", operation)
            .param("key", key)
            .query(
                (rs, rowNum) ->
                    new StoredRecord(
                        rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant()))
            .optional()
            .orElse(null);
    if (stored != null) {
      if (now.isBefore(stored.createdAt().plus(RETENTION))) {
        if (!stored.requestHash().equals(hash)) {
          throw BusinessException.withFieldErrors(
              ErrorCode.INVALID_REQUEST,
              List.of(
                  new FieldErrorItem(
                      HEADER, FieldErrorCode.NOT_ALLOWED, "같은 멱등 키로 다른 내용의 요청을 보낼 수 없습니다.")));
        }
        return objectMapper.readValue(stored.responseBody(), responseType);
      }
      jdbcClient
          .sql(
              "DELETE FROM idempotency_record WHERE membership_id = :membershipId"
                  + " AND operation = :operation AND idempotency_key = :key")
          .param("membershipId", membershipId)
          .param("operation", operation)
          .param("key", key)
          .update();
    }
    T result = action.get();
    jdbcClient
        .sql(
            "INSERT INTO idempotency_record (office_id, membership_id, operation,"
                + " idempotency_key, request_hash, response_status, response_body, created_at)"
                + " VALUES (:officeId, :membershipId, :operation, :key, :hash, 201,"
                + " CAST(:body AS jsonb), :now)")
        .param("officeId", principal.officeId())
        .param("membershipId", membershipId)
        .param("operation", operation)
        .param("key", key)
        .param("hash", hash)
        .param("body", objectMapper.writeValueAsString(result))
        .param("now", java.sql.Timestamp.from(now))
        .update();
    return result;
  }

  private record StoredRecord(String requestHash, String responseBody, Instant createdAt) {}

  private static String hash(JsonNode body) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(String.valueOf(body).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
