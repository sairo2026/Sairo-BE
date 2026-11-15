package com.sairo.be.domain.contract.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.contract.service.ContractExpiryNoticeService;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.sql.Date;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ContractExpiryNoticeIntegrationTest extends ContractIntegrationTestBase {

  @Autowired private ContractExpiryNoticeService noticeService;

  private int sequence;

  private long contractEndingIn(long managerMembership, long daysLeft) throws Exception {
    sequence++;
    long propertyId =
        addressedProperty(ownerCookie, "만기 매물" + sequence, "서울시 마포구 합정동 " + (100 + sequence));
    long id =
        create(
                propertyId,
                jeonse(managerMembership, today(), today().minusDays(5), today().plusYears(2), ""),
                ownerCookie)
            .get("contractId")
            .longValue();
    setEnd(id, daysLeft);
    return id;
  }

  private void setEnd(long contractId, long daysLeft) {
    jdbcTemplate.update(
        "UPDATE contract SET lease_end_date = ? WHERE id = ?",
        Date.valueOf(today().plusDays(daysLeft)),
        contractId);
  }

  private long notices(long contractId) {
    return longValue(
        "SELECT count(*) FROM notification WHERE notification_type = 'CONTRACT_EXPIRY' AND"
            + " target_id = ?",
        contractId);
  }

  private long noticesOf(long contractId, long userId) {
    return longValue(
        "SELECT count(*) FROM notification WHERE notification_type = 'CONTRACT_EXPIRY' AND"
            + " target_id = ? AND recipient_user_id = ?",
        contractId,
        userId);
  }

  private JsonNode notificationItems(Cookie cookie) throws Exception {
    return getJson("/api/notifications", cookie).get("items");
  }

  @ParameterizedTest
  @CsvSource({
    "91,false,",
    "90,true,90일 남았습니다",
    "31,true,31일 남았습니다",
    "30,true,30일 남았습니다",
    "8,true,8일 남았습니다",
    "7,true,7일 남았습니다",
    "1,true,1일 남았습니다",
    "0,true,오늘입니다",
    "-1,false,"
  })
  void 만기까지_남은_일수가_단계_경계에_들어오면_알림을_만든다(long daysLeft, boolean created, String phrase)
      throws Exception {
    long id = contractEndingIn(ownerMembership, daysLeft);

    noticeService.publishDue();

    assertThat(notices(id)).isEqualTo(created ? 1 : 0);
    if (created) {
      String message =
          text(
              "SELECT message FROM notification WHERE notification_type = 'CONTRACT_EXPIRY' AND"
                  + " target_id = ?",
              id);
      assertThat(message).startsWith("만기 매물").endsWith(phrase).doesNotContain("김임대", "이임차");
    }
  }

  @Test
  void 알림함_목록은_계약을_이동_대상으로_내려주고_이메일_전달_행을_만들지_않는다() throws Exception {
    long id = contractEndingIn(ownerMembership, 30);

    noticeService.publishDue();

    JsonNode items = notificationItems(ownerCookie);
    assertThat(items.size()).isEqualTo(1);
    assertThat(items.get(0).get("type").asString()).isEqualTo("CONTRACT_EXPIRY");
    assertThat(items.get(0).get("targetType").asString()).isEqualTo("CONTRACT");
    assertThat(items.get(0).get("targetId").longValue()).isEqualTo(id);
    assertThat(items.get(0).get("read").booleanValue()).isFalse();
    assertThat(items.get(0).get("message").asString()).endsWith("30일 남았습니다");
    assertThat(
            longValue(
                "SELECT count(*) FROM notification_delivery d JOIN notification n ON n.id ="
                    + " d.notification_id WHERE n.notification_type = 'CONTRACT_EXPIRY'"))
        .isZero();
  }

  @Test
  void 같은_계약의_같은_단계는_다시_실행해도_한_번만_만들고_다음_단계에서_새로_만든다() throws Exception {
    long id = contractEndingIn(ownerMembership, 90);

    noticeService.publishDue();
    noticeService.publishDue();
    assertThat(notices(id)).isEqualTo(1);

    setEnd(id, 60);
    noticeService.publishDue();
    assertThat(notices(id)).isEqualTo(1);

    setEnd(id, 30);
    noticeService.publishDue();
    noticeService.publishDue();
    assertThat(notices(id)).isEqualTo(2);

    setEnd(id, 7);
    noticeService.publishDue();
    noticeService.publishDue();
    assertThat(notices(id)).isEqualTo(3);

    setEnd(id, 0);
    noticeService.publishDue();
    assertThat(notices(id)).isEqualTo(3);
  }

  @Test
  void 늦게_등록된_계약은_현재_단계를_한_번만_받는다() throws Exception {
    long id = contractEndingIn(ownerMembership, 20);

    noticeService.publishDue();
    noticeService.publishDue();

    assertThat(notices(id)).isEqualTo(1);
    assertThat(
            text(
                "SELECT message FROM notification WHERE notification_type = 'CONTRACT_EXPIRY' AND"
                    + " target_id = ?",
                id))
        .endsWith("20일 남았습니다");
  }

  @Test
  void 담당_중개사와_대표에게_각각_한_건이고_같은_사람이면_한_건이다() throws Exception {
    TestData.Staff agent = staffIn(owner.office(), OfficeMembershipRole.AGENT);
    TestData.Staff deputy = staffIn(owner.office(), OfficeMembershipRole.DEPUTY);
    long agentManaged = contractEndingIn(agent.membership().getId(), 7);
    long ownerManaged = contractEndingIn(ownerMembership, 7);

    noticeService.publishDue();

    assertThat(notices(agentManaged)).isEqualTo(2);
    assertThat(noticesOf(agentManaged, agent.user().getId())).isEqualTo(1);
    assertThat(noticesOf(agentManaged, owner.user().getId())).isEqualTo(1);
    assertThat(noticesOf(agentManaged, deputy.user().getId())).isZero();
    assertThat(notices(ownerManaged)).isEqualTo(1);
    assertThat(noticesOf(ownerManaged, owner.user().getId())).isEqualTo(1);
    assertThat(notificationItems(cookieOf(agent)).size()).isEqualTo(1);
    assertThat(notificationItems(ownerCookie).size()).isEqualTo(2);
    assertThat(notificationItems(cookieOf(deputy)).size()).isZero();
  }

  @Test
  void 종료와_해지된_계약과_만기일이_없는_매매는_알림을_만들지_않는다() throws Exception {
    long ended = contractEndingIn(ownerMembership, 10);
    long terminated = contractEndingIn(ownerMembership, 10);
    long cancelled = contractEndingIn(ownerMembership, 10);
    jdbcTemplate.update("UPDATE contract SET contract_status = 'ENDED' WHERE id = ?", ended);
    jdbcTemplate.update(
        "UPDATE contract SET contract_status = 'TERMINATED' WHERE id = ?", terminated);
    jdbcTemplate.update(
        "UPDATE contract SET contract_status = 'CANCELLED' WHERE id = ?", cancelled);
    long saleProperty = addressedProperty(ownerCookie, "매매 매물", "서울시 마포구 합정동 3");
    long sale =
        create(saleProperty, sale(ownerMembership, today(), ""), ownerCookie)
            .get("contractId")
            .longValue();

    noticeService.publishDue();

    assertThat(notices(ended)).isZero();
    assertThat(notices(terminated)).isZero();
    assertThat(notices(cancelled)).isZero();
    assertThat(notices(sale)).isZero();
  }

  @Test
  void 홈_후속업무_응답은_계약_ID를_담는다() throws Exception {
    long near = createJeonse(property, today().minusDays(5), today().plusDays(60));
    long other = contractEndingIn(ownerMembership, 40);
    jdbcTemplate.update(
        "INSERT INTO contract_followup_task (contract_id, task_type, submitted, created_at)"
            + " VALUES (?, 'D90_FOLLOWUP', false, now())",
        other);

    JsonNode followups = getJson("/api/home", ownerCookie).get("openFollowupTasks");

    assertThat(followups.size()).isEqualTo(2);
    Set<Long> contractIds = new HashSet<>();
    for (JsonNode item : followups) {
      contractIds.add(item.get("contractId").longValue());
    }
    assertThat(contractIds).containsExactlyInAnyOrder(near, other);
  }
}
