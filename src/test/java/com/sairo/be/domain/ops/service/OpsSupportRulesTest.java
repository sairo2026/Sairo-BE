package com.sairo.be.domain.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sairo.be.domain.support.entity.InquiryPriority;
import com.sairo.be.domain.support.entity.LeadStatus;
import com.sairo.be.domain.support.service.ResponseDueCalculator;
import com.sairo.be.global.error.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OpsSupportRulesTest {

  @Test
  void 회원_이름은_첫_글자와_마지막_글자만_남긴다() {
    assertThat(OpsDisplayNames.member("홍길동", false)).isEqualTo("홍*동");
    assertThat(OpsDisplayNames.member("남궁민수", false)).isEqualTo("남**수");
    assertThat(OpsDisplayNames.member("이수", false)).isEqualTo("이*");
    assertThat(OpsDisplayNames.member("김", false)).isEqualTo("*");
    assertThat(OpsDisplayNames.member("홍길동", true)).isEqualTo(OpsDisplayNames.WITHDRAWN);
    assertThat(OpsDisplayNames.member(" ", false)).isEqualTo(OpsDisplayNames.UNKNOWN);
  }

  @Test
  void 재개는_견적_단계면_상담으로_그_밖에는_종료_직전_단계로_돌아간다() {
    assertThat(OpsEnterpriseLeadService.reopenTarget(LeadStatus.QUOTED))
        .isEqualTo(LeadStatus.CONSULTING);
    assertThat(OpsEnterpriseLeadService.reopenTarget(LeadStatus.ACCEPTED))
        .isEqualTo(LeadStatus.CONSULTING);
    assertThat(OpsEnterpriseLeadService.reopenTarget(LeadStatus.REJECTED))
        .isEqualTo(LeadStatus.CONSULTING);
    assertThat(OpsEnterpriseLeadService.reopenTarget(LeadStatus.ASSIGNED))
        .isEqualTo(LeadStatus.ASSIGNED);
    assertThat(OpsEnterpriseLeadService.reopenTarget(LeadStatus.RECEIVED))
        .isEqualTo(LeadStatus.RECEIVED);
    assertThatThrownBy(() -> OpsEnterpriseLeadService.reopenTarget(LeadStatus.CONTRACTED))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void 우선순위별_첫_답변_목표는_높음_4시간_보통_1영업일_낮음_2영업일이다() {
    Instant friday = Instant.parse("2026-11-13T01:00:00Z");
    assertThat(ResponseDueCalculator.firstResponseDue(friday, InquiryPriority.HIGH))
        .isEqualTo(Instant.parse("2026-11-13T05:00:00Z"));
    assertThat(ResponseDueCalculator.firstResponseDue(friday, InquiryPriority.NORMAL))
        .isEqualTo(Instant.parse("2026-11-16T01:00:00Z"));
    assertThat(ResponseDueCalculator.firstResponseDue(friday, InquiryPriority.LOW))
        .isEqualTo(Instant.parse("2026-11-17T01:00:00Z"));
  }
}
