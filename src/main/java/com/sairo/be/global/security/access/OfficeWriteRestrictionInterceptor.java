package com.sairo.be.global.security.access;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.OfficeWriteRestriction;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 인증 회수, 결제 전, 미납 제한, 구독 종료로 읽기만 가능한 사무소의 업무 데이터 쓰기를 서버에서 막는다. 화면이 이미 열려 있거나 API를 직접 호출해도 같은 결과다.
 * 결제·내보내기·문의·계정처럼 제한 중에도 써야 하는 경로는 대상에 넣지 않는다.
 */
@Profile("!migrate")
@Component
public class OfficeWriteRestrictionInterceptor implements HandlerInterceptor {

  private static final PathPatternParser PARSER = new PathPatternParser();

  private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

  private static final List<PathPattern> RESTRICTED_PATHS =
      List.of(
              "/api/properties/**",
              "/api/property-categories/**",
              "/api/customers/**",
              "/api/customer-consultations/**",
              "/api/customer-property-interests/**",
              "/api/demand-conditions/**",
              "/api/owner-occupants/**",
              "/api/contracts/**",
              "/api/contract-fees/**",
              "/api/contract-followup-tasks/**",
              "/api/payment-schedules/**",
              "/api/tasks/**",
              "/api/settlements/**",
              "/api/entity-lifecycle-events/**",
              "/api/import-batches/**",
              "/api/visit-coordinations/**",
              "/api/visit-requests/**",
              "/api/visit-reviews/**",
              "/api/visit-schedule-items/**")
          .stream()
          .map(PARSER::parse)
          .toList();

  private final OfficeWriteRestriction writeRestriction;

  public OfficeWriteRestrictionInterceptor(OfficeWriteRestriction writeRestriction) {
    this.writeRestriction = writeRestriction;
  }

  public static boolean isRestrictedPath(String method, String path) {
    return MUTATING_METHODS.contains(method.toUpperCase())
        && RESTRICTED_PATHS.stream().anyMatch(p -> p.matches(PathContainer.parsePath(path)));
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!isRestrictedPath(request.getMethod(), request.getRequestURI())) {
      return true;
    }
    AccessSubject subject =
        AccessSubject.from(SecurityContextHolder.getContext().getAuthentication());
    if (!subject.hasApprovedMembership()) {
      return true;
    }
    if (writeRestriction.businessDataWriteBlocked(subject.officeId())) {
      throw new BusinessException(ErrorCode.OFFICE_WRITE_RESTRICTED);
    }
    return true;
  }
}
