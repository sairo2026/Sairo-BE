package com.sairo.be.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;

class ScheduledLockDeclarationTest {

  private static final int LOCK_NAME_COLUMN_LENGTH = 64;
  private static final Pattern LOCK_NAME = Pattern.compile("^[a-z][a-z0-9-]*$");

  private static final List<Method> SCHEDULED_METHODS = scheduledMethods();

  private static List<Method> scheduledMethods() {
    JavaClasses applicationClasses =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.sairo.be");
    return applicationClasses.stream()
        .flatMap(javaClass -> javaClass.getMethods().stream())
        .filter(
            method ->
                method.isAnnotatedWith(Scheduled.class) || method.isAnnotatedWith(Schedules.class))
        .map(JavaMethod::reflect)
        .toList();
  }

  private static String describe(Method method) {
    return method.getDeclaringClass().getSimpleName() + "." + method.getName();
  }

  @Test
  void 예약_배치_메서드를_찾는다() {
    assertThat(SCHEDULED_METHODS).isNotEmpty();
  }

  @Test
  void 모든_예약_배치_메서드에_잠금_선언이_있다() {
    assertThat(SCHEDULED_METHODS)
        .allSatisfy(
            method ->
                assertThat(method.getAnnotation(SchedulerLock.class))
                    .as(describe(method))
                    .isNotNull());
  }

  @Test
  void 잠금_이름은_겹치지_않고_잠금_표의_이름_열에_들어간다() {
    List<String> names =
        SCHEDULED_METHODS.stream()
            .map(method -> method.getAnnotation(SchedulerLock.class).name())
            .toList();

    assertThat(names).doesNotHaveDuplicates();
    assertThat(names)
        .allSatisfy(
            name -> {
              assertThat(name).matches(LOCK_NAME);
              assertThat(name.length()).isLessThanOrEqualTo(LOCK_NAME_COLUMN_LENGTH);
            });
  }

  @Test
  void 잠금_유지_시간은_최대가_최소보다_길고_최소는_반복_간격보다_짧다() {
    assertThat(SCHEDULED_METHODS)
        .allSatisfy(
            method -> {
              SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
              Duration atMost = Duration.parse(lock.lockAtMostFor());
              Duration atLeast = Duration.parse(lock.lockAtLeastFor());
              assertThat(atLeast).as(describe(method)).isPositive();
              assertThat(atMost).as(describe(method)).isGreaterThan(atLeast);
              Scheduled scheduled = method.getAnnotation(Scheduled.class);
              if (scheduled != null && !scheduled.fixedDelayString().isEmpty()) {
                assertThat(atLeast)
                    .as(describe(method))
                    .isLessThan(Duration.parse(scheduled.fixedDelayString()));
              }
            });
  }

  @Test
  void 잠금을_거는_프록시가_감쌀_수_있게_예약_배치_메서드와_클래스는_공개이고_final이_아니다() {
    assertThat(SCHEDULED_METHODS)
        .allSatisfy(
            method -> {
              assertThat(Modifier.isPublic(method.getModifiers())).as(describe(method)).isTrue();
              assertThat(Modifier.isFinal(method.getModifiers())).as(describe(method)).isFalse();
              assertThat(Modifier.isFinal(method.getDeclaringClass().getModifiers()))
                  .as(describe(method))
                  .isFalse();
            });
  }
}
