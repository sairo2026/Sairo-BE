package com.sairo.be.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.security.access.RequiresOperatorRole;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 모든 HTTP 핸들러가 접근 권한 인터셉터의 경로(/api/**) 안에 있고 경로 영역과 권한 값이 맞는지 정적으로 검사한다. 인터셉터는 /api/** 에만 걸리므로 그 밖의
 * 핸들러는 권한 검사를 받지 않는다.
 */
class ApiAccessDeclarationTest {

  private static final String BASE = "com.sairo.be";

  private static final List<String> PUBLIC_PREFIXES =
      List.of(
          "/api/public/",
          "/api/auth/",
          "/api/ops/auth/",
          "/api/enterprise-leads",
          "/api/grant-auth/",
          "/api/policy-documents",
          "/api/terms");

  private record Handler(String method, String path, AccessPermission permission, boolean role) {

    @Override
    public String toString() {
      return method + " " + path + " " + permission;
    }
  }

  private static List<Handler> handlers;
  private static List<Class<?>> controllerClasses;

  @BeforeAll
  static void scan() throws ClassNotFoundException {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
    controllerClasses = new ArrayList<>();
    handlers = new ArrayList<>();
    for (BeanDefinition definition : scanner.findCandidateComponents(BASE)) {
      Class<?> type = ClassUtils.forName(definition.getBeanClassName(), null);
      if (type.getName().contains(".support.") || !type.getName().contains(".domain.")) {
        continue;
      }
      controllerClasses.add(type);
      RequestMapping classMapping =
          AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
      String[] bases = classMapping == null ? new String[] {""} : paths(classMapping);
      RequiresAccess classAccess =
          AnnotatedElementUtils.findMergedAnnotation(type, RequiresAccess.class);
      boolean classRole = AnnotatedElementUtils.hasAnnotation(type, RequiresOperatorRole.class);
      for (Method method : type.getDeclaredMethods()) {
        RequestMapping mapping =
            AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (mapping == null) {
          continue;
        }
        RequiresAccess access =
            AnnotatedElementUtils.findMergedAnnotation(method, RequiresAccess.class);
        if (access == null) {
          access = classAccess;
        }
        boolean role =
            classRole || AnnotatedElementUtils.hasAnnotation(method, RequiresOperatorRole.class);
        String verb = mapping.method().length == 0 ? "ANY" : mapping.method()[0].name();
        for (String base : bases) {
          for (String path : paths(mapping)) {
            handlers.add(
                new Handler(verb, base + path, access == null ? null : access.value(), role));
          }
        }
      }
    }
  }

  private static String[] paths(RequestMapping mapping) {
    String[] paths = mapping.path().length > 0 ? mapping.path() : mapping.value();
    return paths.length == 0 ? new String[] {""} : paths;
  }

  private static List<Handler> where(java.util.function.Predicate<Handler> filter) {
    return handlers.stream().filter(filter).toList();
  }

  @Test
  void 핸들러를_빠짐없이_찾는다() {
    assertThat(controllerClasses).hasSizeGreaterThan(50);
    assertThat(handlers).hasSizeGreaterThan(400);
  }

  @Test
  void 모든_핸들러는_권한_인터셉터가_걸리는_api_경로_안에_있다() {
    assertThat(where(handler -> !handler.path().startsWith("/api/"))).isEmpty();
  }

  @Test
  void 모든_핸들러는_권한_값을_선언한다() {
    assertThat(where(handler -> handler.permission() == null)).isEmpty();
  }

  @Test
  void 운영_콘솔_경로는_운영자_권한이고_운영자_권한은_운영_콘솔_밖에_쓰지_않는다() {
    assertThat(
            where(
                handler ->
                    handler.path().startsWith("/api/ops/")
                        && !handler.path().startsWith("/api/ops/auth/")
                        && handler.permission() != AccessPermission.OPERATOR))
        .isEmpty();
    assertThat(
            where(
                handler ->
                    handler.permission() == AccessPermission.OPERATOR
                        && !handler.path().startsWith("/api/ops/")
                        && !(handler.path().equals("/api/archive-grants")
                            && handler.method().equals("POST"))))
        .isEmpty();
    assertThat(
            where(
                handler ->
                    handler.path().startsWith("/api/ops/auth/")
                        && !EnumSet.of(AccessPermission.PUBLIC, AccessPermission.OPERATOR)
                            .contains(handler.permission())))
        .isEmpty();
  }

  @Test
  void 운영자_역할_선언은_운영자_권한_핸들러에만_있다() {
    assertThat(
            where(handler -> handler.role() && handler.permission() != AccessPermission.OPERATOR))
        .isEmpty();
  }

  @Test
  void 공개_권한은_허용된_공개_경로에만_있다() {
    assertThat(
            where(
                handler ->
                    handler.permission() == AccessPermission.PUBLIC
                        && PUBLIC_PREFIXES.stream().noneMatch(handler.path()::startsWith)))
        .isEmpty();
    assertThat(
            where(
                handler ->
                    handler.path().startsWith("/api/public/")
                        && !EnumSet.of(AccessPermission.PUBLIC, AccessPermission.GUEST_LINK)
                            .contains(handler.permission())))
        .isEmpty();
  }

  @Test
  void 링크_세션_권한과_경로_영역이_서로_맞다() {
    assertPairs(AccessPermission.GUEST_LINK, Set.of("/api/public/"), false);
    assertPairs(AccessPermission.APPROVER_SESSION, Set.of("/api/approver-portal/"), true);
    assertPairs(AccessPermission.RETURN_GRANT, Set.of("/api/return-grants/"), true);
    assertPairs(AccessPermission.ARCHIVE_GRANT, Set.of("/api/archive-grants/"), false);
    assertPairs(AccessPermission.EXTERNAL_WEBHOOK, Set.of("/api/webhooks/"), true);
  }

  /** 권한 값이 정해진 경로 영역 밖에 없고, exclusive이면 그 영역의 모든 핸들러가 그 권한이다. */
  private void assertPairs(AccessPermission permission, Set<String> prefixes, boolean exclusive) {
    assertThat(
            where(
                handler ->
                    handler.permission() == permission
                        && prefixes.stream().noneMatch(handler.path()::startsWith)))
        .as(permission + " 권한이 영역 밖에 있다")
        .isEmpty();
    if (exclusive) {
      assertThat(
              where(
                  handler ->
                      prefixes.stream().anyMatch(handler.path()::startsWith)
                          && handler.permission() != permission))
          .as(prefixes + " 영역에 다른 권한이 있다")
          .isEmpty();
    }
  }

  @Test
  void 오류_응답은_공통_처리기_한_곳에서만_만든다() throws ClassNotFoundException {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(ControllerAdvice.class));
    List<String> advices = new ArrayList<>();
    for (BeanDefinition definition : scanner.findCandidateComponents(BASE)) {
      if (!definition.getBeanClassName().contains(".support.")) {
        advices.add(definition.getBeanClassName());
      }
    }
    assertThat(advices).containsExactly(BASE + ".global.error.GlobalExceptionHandler");
    for (Class<?> type : controllerClasses) {
      for (Method method : type.getDeclaredMethods()) {
        assertThat(AnnotatedElementUtils.hasAnnotation(method, ExceptionHandler.class))
            .as(type.getSimpleName() + "." + method.getName() + "에 별도 예외 처리기가 있다")
            .isFalse();
      }
    }
  }
}
