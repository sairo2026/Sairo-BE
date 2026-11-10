package com.sairo.be.global.security.access;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.OperatorAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
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
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    AccessSubject subject = AccessSubject.from(authentication);
    accessPolicy.check(
        new AccessRequest(
            annotation.value(), subject, request.getMethod(), request.getRequestURI()));
    RequiresOperatorRole roleAnnotation = findRoleAnnotation(handlerMethod);
    if (roleAnnotation != null && !hasAnyRole(authentication, roleAnnotation)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    return true;
  }

  private static boolean hasAnyRole(Authentication authentication, RequiresOperatorRole required) {
    if (!(authentication instanceof OperatorAuthentication operator)) {
      return false;
    }
    for (var role : required.value()) {
      if (operator.getPrincipal().hasRole(role)) {
        return true;
      }
    }
    return false;
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

  private static RequiresOperatorRole findRoleAnnotation(HandlerMethod handlerMethod) {
    RequiresOperatorRole onMethod =
        AnnotatedElementUtils.findMergedAnnotation(
            handlerMethod.getMethod(), RequiresOperatorRole.class);
    if (onMethod != null) {
      return onMethod;
    }
    return AnnotatedElementUtils.findMergedAnnotation(
        handlerMethod.getBeanType(), RequiresOperatorRole.class);
  }
}
