package com.sairo.be.domain.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sairo.be.domain.visit.service.ApproverConditionParser.Mode;
import com.sairo.be.domain.visit.service.ApproverConditionParser.Parsed;
import com.sairo.be.domain.visit.service.ConditionSpec.Window;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ApproverConditionParserTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static JsonNode json(String windows, String extra) {
    return MAPPER.readTree(
        "{\"appliesFrom\":\"2030-01-01T00:00:00+09:00\",\"appliesUntil\":\"2030-03-01T00:00:00+09:00\","
            + "\"windows\":"
            + windows
            + ",\"impactAcknowledged\":false,\"acknowledgedNoticeVersion\":\"1.0\""
            + extra
            + "}");
  }

  private static String weekday(int day, String from, String to, boolean exception) {
    return "{\"windowType\":\"WEEKDAY\",\"weekday\":"
        + day
        + ",\"startsTime\":\""
        + from
        + "\",\"endsTime\":\""
        + to
        + "\",\"isException\":"
        + exception
        + "}";
  }

  @Test
  void 겹치거나_맞닿는_구간은_같은_요일과_제외_여부끼리만_합친다() {
    String windows =
        "["
            + weekday(1, "09:00", "10:00", false)
            + ","
            + weekday(1, "10:00", "11:00", false)
            + ","
            + weekday(1, "10:30", "10:45", true)
            + ","
            + weekday(2, "09:00", "10:00", false)
            + ","
            + weekday(1, "13:00", "14:00", false)
            + "]";

    Parsed parsed = ApproverConditionParser.parse(json(windows, ""), Mode.LINK_SAVE);

    assertThat(parsed.spec().windows()).hasSize(4);
    assertThat(parsed.normalizedRanges()).hasSize(1);
    Window merged = parsed.normalizedRanges().get(0);
    assertThat(merged.weekday()).isEqualTo(1);
    assertThat(merged.startMinute()).isEqualTo(9 * 60);
    assertThat(merged.endMinute()).isEqualTo(11 * 60);
    assertThat(merged.exception()).isFalse();
  }

  @Test
  void 영향_미리보기는_안내_버전과_확인_여부가_없어도_받는다() {
    JsonNode body =
        MAPPER.readTree(
            "{\"appliesFrom\":\"2030-01-01T00:00:00+09:00\",\"appliesUntil\":\"2030-03-01T00:00:00+09:00\","
                + "\"windows\":[]}");

    Parsed parsed = ApproverConditionParser.parse(body, Mode.LINK_PREVIEW);

    assertThat(parsed.impactAcknowledged()).isFalse();
    assertThat(parsed.acknowledgedNoticeVersion()).isNull();
    assertThat(parsed.spec().windows()).isEmpty();
  }

  @Test
  void 날짜별_규칙은_배열_상한을_넘으면_거절한다() {
    StringBuilder rules = new StringBuilder(",\"dateRules\":[");
    for (int i = 0; i < 367; i++) {
      rules.append(i == 0 ? "" : ",");
      rules.append(
          "{\"ruleDate\":\"2030-01-05\",\"minGapMinutes\":{\"kind\":\"VALUE\",\"value\":1}}");
    }
    rules.append("]");

    assertThatThrownBy(
            () -> ApproverConditionParser.parse(json("[]", rules.toString()), Mode.LINK_SAVE))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
  }
}
