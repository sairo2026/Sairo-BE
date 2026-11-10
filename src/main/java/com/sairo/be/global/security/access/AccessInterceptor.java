package com.sairo.be.global.security.access;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Profile("!migrate")
@Component
public class AccessInterceptor implements HandlerInterceptor {

  private final AccessPolicy accessPolicy;

  public AccessInterceptor(AccessPolicy accessPolicy) {
    this.accessPolicy = accessPolicy;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod handlerMethod)) {
      return true;
    }
    RequiresAccess annotation = findAnnotation(handlerMethod);
    if (annotation == null) {
      log.error("접근 권한 값이 선언되지 않은 핸들러입니다. handler={}", handlerMethod.getShortLogMessage());
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    AccessSubject subject =
        AccessSubject.from(SecurityContextHolder.getContext().getAuthentication());
    accessPolicy.check(
        new AccessRequest(
            annotation.value(), subject, request.getMethod(), request.getRequestURI()));
    return true;
  }

  public static RequiresAccess findAnnotation(HandlerMethod handlerMethod) {
    RequiresAccess onMethod =
        AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), RequiresAccess.class);
    if (onMethod != null) {
      return onMethod;
    }
    return AnnotatedElementUtils.findMergedAnnotation(
        handlerMethod.getBeanType(), RequiresAccess.class);
  }
}
