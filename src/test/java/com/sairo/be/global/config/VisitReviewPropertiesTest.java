package com.sairo.be.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class VisitReviewPropertiesTest {

  private static final String PREFIX = "sairo.visit.review";

  private static VisitReviewProperties bind(Map<String, String> values) {
    return new Binder(new MapConfigurationPropertySource(values))
        .bindOrCreate(PREFIX, Bindable.of(VisitReviewProperties.class));
  }

  @Test
  void 설정이_없으면_확정된_평가_정책_값을_쓴다() {
    VisitReviewProperties properties = bind(Map.of());

    assertThat(properties).isEqualTo(VisitReviewProperties.defaults());
    assertThat(properties.reviewWindowDays()).isEqualTo(14);
    assertThat(properties.reviewWindow()).isEqualTo(Duration.ofDays(14));
    assertThat(properties.maxRevisions()).isEqualTo(2);
    assertThat(properties.disputeWindow()).isEqualTo(Duration.ofDays(30));
    assertThat(properties.disputeTargetBusinessDays()).isEqualTo(7);
    assertThat(properties.disputeDueTime()).isEqualTo(LocalTime.of(18, 0));
    assertThat(properties.preferredBrokerLimit()).isEqualTo(5);
    assertThat(properties.approverListWindow()).isEqualTo(Duration.ofDays(90));
  }

  @Test
  void 기본_설정_파일의_값은_확정된_평가_정책_값과_같다() throws IOException {
    List<PropertySource<?>> sources =
        new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"));

    VisitReviewProperties properties =
        new Binder(ConfigurationPropertySources.from(sources))
            .bind(PREFIX, Bindable.of(VisitReviewProperties.class))
            .get();

    assertThat(properties).isEqualTo(VisitReviewProperties.defaults());
  }

  @Test
  void 설정으로_값을_바꿀_수_있다() {
    VisitReviewProperties properties =
        bind(
            Map.of(
                PREFIX + ".review-window-days", "21",
                PREFIX + ".max-revisions", "1",
                PREFIX + ".dispute-due-hour", "17",
                PREFIX + ".preferred-broker-limit", "3"));

    assertThat(properties.reviewWindow()).isEqualTo(Duration.ofDays(21));
    assertThat(properties.maxRevisions()).isEqualTo(1);
    assertThat(properties.disputeDueTime()).isEqualTo(LocalTime.of(17, 0));
    assertThat(properties.preferredBrokerLimit()).isEqualTo(3);
    assertThat(properties.disputeWindowDays()).isEqualTo(30);
  }

  @Test
  void 수정_횟수_상한은_스키마_제약을_넘을_수_없고_기간과_상한은_1_이상이어야_한다() {
    assertThatThrownBy(() -> new VisitReviewProperties(14, 3, 30, 7, 18, 5, 90))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new VisitReviewProperties(14, -1, 30, 7, 18, 5, 90))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new VisitReviewProperties(0, 2, 30, 7, 18, 5, 90))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new VisitReviewProperties(14, 2, 30, 7, 18, 0, 90))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new VisitReviewProperties(14, 2, 30, 7, 24, 5, 90))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
