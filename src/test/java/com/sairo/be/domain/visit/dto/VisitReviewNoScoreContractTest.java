package com.sairo.be.domain.visit.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.visit.dto.response.ApproverVisitRequestCompareResponse;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class VisitReviewNoScoreContractTest {

  private static final Set<String> FORBIDDEN_WORDS =
      Set.of("score", "scores", "rating", "ratings", "rank", "ranking", "star", "stars");

  @Test
  void 평가_신뢰_선호_비교_응답에는_점수_별점_순위_필드가_없다() {
    List<String> names = new ArrayList<>();
    Set<Class<?>> seen = new HashSet<>();
    Stream.concat(
            Stream.of(VisitReviewResponses.class.getDeclaredClasses()),
            Stream.of(ApproverVisitRequestCompareResponse.class))
        .forEach(type -> collect(type, names, seen));

    assertThat(names).contains("applicantTrust", "overallBand", "isPreferredBroker", "answers");
    for (String name : names) {
      for (String word : name.split("(?<=[a-z0-9])(?=[A-Z])")) {
        assertThat(FORBIDDEN_WORDS).as("응답 필드 " + name).doesNotContain(word.toLowerCase());
      }
    }
  }

  private static void collect(Class<?> type, List<String> names, Set<Class<?>> seen) {
    if (!type.isRecord() || !seen.add(type)) {
      return;
    }
    for (RecordComponent component : type.getRecordComponents()) {
      names.add(component.getName());
      collect(component.getType(), names, seen);
      Type generic = component.getGenericType();
      if (generic instanceof ParameterizedType parameterized) {
        for (Type argument : parameterized.getActualTypeArguments()) {
          if (argument instanceof Class<?> nested) {
            collect(nested, names, seen);
          }
        }
      }
    }
  }
}
