package com.sairo.be.domain.settlement.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.customer.service.SensitiveDataDetector;
import com.sairo.be.domain.settlement.dto.response.MonthlyMemoResponse;
import com.sairo.be.domain.settlement.repository.MonthlyMemoRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.InputRules;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** 사무소·월당 메모 하나다. 작성 전 달은 version 0으로 보이고 If-Match 0으로 처음 쓴다. */
@Profile("!migrate")
@Service
public class MonthlyMemoService {

  static final int CONTENT_MAX = 2_000;
  private static final Set<String> KEYS = Set.of("content");

  private final MonthlyMemoRepository memoRepository;
  private final SettlementService settlementService;
  private final Clock clock;

  public MonthlyMemoService(
      MonthlyMemoRepository memoRepository, SettlementService settlementService, Clock clock) {
    this.memoRepository = memoRepository;
    this.settlementService = settlementService;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public MonthlyMemoResponse get(StaffPrincipal principal, String yearMonth) {
    YearMonth month = settlementService.month(principal.officeId(), yearMonth, clock.instant());
    return read(principal.officeId(), month);
  }

  @Transactional
  public MonthlyMemoResponse put(
      StaffPrincipal principal, String yearMonth, String ifMatch, JsonNode body) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    YearMonth month = settlementService.month(principal.officeId(), yearMonth, now);
    long expected = parseIfMatch(ifMatch);
    String content = parseContent(body);
    boolean saved =
        expected == 0
            ? memoRepository.insert(
                principal.officeId(), month, content, principal.membershipId(), now)
            : memoRepository.update(
                principal.officeId(), month, expected, content, principal.membershipId(), now);
    if (!saved) {
      long current = memoRepository.currentVersion(principal.officeId(), month).orElse(0L);
      throw BusinessException.versionConflict(current);
    }
    return read(principal.officeId(), month);
  }

  private MonthlyMemoResponse read(Long officeId, YearMonth month) {
    return memoRepository
        .find(officeId, month)
        .map(
            row ->
                new MonthlyMemoResponse(
                    month.toString(),
                    row.content(),
                    row.version(),
                    row.updatedByMembershipId(),
                    row.updatedByMemberName(),
                    TimeRules.toDisplayOffset(row.updatedAt())))
        .orElseGet(() -> new MonthlyMemoResponse(month.toString(), null, 0, null, null, null));
  }

  private static String parseContent(JsonNode body) {
    CustomerInput.requireObject(body, KEYS);
    if (!body.has("content")) {
      throw CustomerInput.invalid("content", FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    JsonNode node = body.get("content");
    if (node.isNull()) {
      return null;
    }
    if (!node.isString()) {
      throw CustomerInput.invalid("content", FieldErrorCode.TYPE_MISMATCH, "문자열이어야 합니다.");
    }
    String value = InputRules.requireText("content", node.asString(), 0, CONTENT_MAX);
    if (value.isEmpty()) {
      return null;
    }
    if (SensitiveDataDetector.detect(value)) {
      throw BusinessException.withFieldErrors(
          ErrorCode.SENSITIVE_DATA_DETECTED,
          List.of(new FieldErrorItem("content", FieldErrorCode.INVALID_VALUE, "민감 번호가 감지됐습니다.")));
    }
    return value;
  }

  static long parseIfMatch(String header) {
    if (header == null || header.isBlank()) {
      throw ifMatchError(FieldErrorCode.REQUIRED, "If-Match 헤더에 현재 version을 보내야 합니다.");
    }
    String value = header.strip();
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      value = value.substring(1, value.length() - 1);
    }
    try {
      long parsed = Long.parseLong(value);
      if (parsed < 0) {
        throw ifMatchError(FieldErrorCode.OUT_OF_RANGE, "If-Match 값은 0 이상의 십진수여야 합니다.");
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw ifMatchError(FieldErrorCode.INVALID_FORMAT, "If-Match 값은 십진수여야 합니다.");
    }
  }

  private static BusinessException ifMatchError(FieldErrorCode code, String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST, List.of(new FieldErrorItem("If-Match", code, message)));
  }
}
