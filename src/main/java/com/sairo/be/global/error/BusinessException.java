package com.sairo.be.global.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

  public enum QuotaScope {
    MEMBERSHIP,
    APPROVAL
  }

  private final ErrorCode errorCode;
  private final List<FieldErrorItem> fieldErrors;
  private final Map<String, Object> details;

  public BusinessException(ErrorCode errorCode) {
    this(errorCode, List.of(), Map.of());
  }

  private BusinessException(
      ErrorCode errorCode, List<FieldErrorItem> fieldErrors, Map<String, Object> details) {
    super(errorCode.getMessage());
    this.errorCode = errorCode;
    this.fieldErrors = List.copyOf(fieldErrors);
    this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
  }

  public static BusinessException withFieldErrors(
      ErrorCode errorCode, List<FieldErrorItem> fieldErrors) {
    return new BusinessException(errorCode, fieldErrors, Map.of());
  }

  public static BusinessException withDetails(ErrorCode errorCode, Map<String, Object> details) {
    return new BusinessException(errorCode, List.of(), details);
  }

  public static BusinessException withActionUrl(ErrorCode errorCode, String actionUrl) {
    return withDetails(errorCode, Map.of("actionUrl", actionUrl));
  }

  public static BusinessException rateLimited(long retryAfterSeconds) {
    return withDetails(ErrorCode.RATE_LIMITED, Map.of("retryAfterSeconds", retryAfterSeconds));
  }

  public static BusinessException visitRequestQuotaExceeded(
      long retryAfterSeconds, QuotaScope scope, int limit, Long current) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("retryAfterSeconds", retryAfterSeconds);
    details.put("quotaScope", scope.name());
    details.put("limit", limit);
    details.put("current", scope == QuotaScope.APPROVAL ? null : current);
    return withDetails(ErrorCode.VISIT_REQUEST_QUOTA_EXCEEDED, details);
  }

  public static BusinessException versionConflict(long currentVersion) {
    return withDetails(ErrorCode.VERSION_CONFLICT, Map.of("currentVersion", currentVersion));
  }

  public static BusinessException verificationCodeInvalid(int remainingAttempts) {
    return withDetails(
        ErrorCode.VERIFICATION_CODE_INVALID, Map.of("remainingAttempts", remainingAttempts));
  }

  public static BusinessException termsVersionChanged(List<?> changedItems) {
    return withDetails(ErrorCode.TERMS_VERSION_CHANGED, Map.of("changedItems", changedItems));
  }

  public static BusinessException approverConditionViolated(List<String> violatedRules) {
    return withDetails(
        ErrorCode.APPROVER_CONDITION_VIOLATED, Map.of("violatedRules", violatedRules));
  }

  public static BusinessException impactNotAcknowledged(List<?> affectedItems) {
    return withDetails(ErrorCode.IMPACT_NOT_ACKNOWLEDGED, Map.of("affectedItems", affectedItems));
  }

  public static BusinessException invitationNotUsable(String invitationState) {
    return withDetails(ErrorCode.INVITATION_NOT_USABLE, Map.of("invitationState", invitationState));
  }
}
