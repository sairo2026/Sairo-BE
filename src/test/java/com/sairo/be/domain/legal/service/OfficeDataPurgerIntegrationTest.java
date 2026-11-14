package com.sairo.be.domain.legal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.legal.service.DeletionTargetService.Outcome;
import com.sairo.be.domain.legal.service.DeletionTargetService.Plan;
import com.sairo.be.global.port.ObjectStorage;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitOutcomeTestBase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 종료된 사무소의 업무 데이터 파기. 시험 데이터만 만들고 지운다. */
@IntegrationTest
class OfficeDataPurgerIntegrationTest extends VisitOutcomeTestBase {

  @Autowired private OfficeDataPurger purger;
  @Autowired private DeletionTargetService targetService;
  @Autowired private ObjectStorage objectStorage;

  private SelectionFixture visit;
  private long officeId;

  @BeforeEach
  void seed() throws Exception {
    visit = confirmed();
    officeId = visit.base().owner().office().getId();
  }

  private long count(String table, String where) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE " + where, Long.class);
  }

  private void closeOffice() {
    jdbcTemplate.update(
        "UPDATE office SET closed_at = now() - interval '60 days' WHERE id = ?", officeId);
  }

  @Test
  void 종료되지_않은_사무소와_반환권한이_살아_있는_사무소는_보류_사유가_나온다() {
    assertThat(purger.holdReasons(officeId, Instant.now())).containsExactly("종료되지 않은 사무소");

    closeOffice();
    assertThat(purger.holdReasons(officeId, Instant.now())).isEmpty();

    jdbcTemplate.update(
        "INSERT INTO return_grant (office_id, granted_to_user_id, expires_at) VALUES (?, ?,"
            + " now() + interval '30 days')",
        officeId,
        visit.base().owner().user().getId());
    assertThat(purger.holdReasons(officeId, Instant.now())).containsExactly("반환권한 유효 기간 중");
  }

  @Test
  void 업무_데이터를_참조하는_쪽부터_지우고_계약이_가리키는_고객과_매물은_남긴다() {
    long customer = visit.base().customer();
    long property = visit.base().propertyId();
    long contract =
        jdbcTemplate.queryForObject(
            "INSERT INTO contract (office_id, property_id, deal_type, managing_membership_id)"
                + " VALUES (?, ?, 'SALE', ?) RETURNING id",
            Long.class,
            officeId,
            property,
            visit.base().owner().membership().getId());
    jdbcTemplate.update(
        "INSERT INTO contract_party (contract_id, side, sequence_no, name, customer_id) VALUES (?,"
            + " 'BUYER_TENANT', 1, '당사자', ?)",
        contract,
        customer);
    assertThat(count("visit_request", "applicant_office_id = " + officeId)).isPositive();

    long deleted = purger.execute(officeId, Instant.now());

    assertThat(deleted).isPositive();
    assertThat(count("visit_request", "applicant_office_id = " + officeId)).isZero();
    assertThat(count("visit_schedule_item", "office_id = " + officeId)).isZero();
    assertThat(count("visit_candidate_property", "office_id = " + officeId)).isZero();
    assertThat(count("visit_coordination", "office_id = " + officeId)).isZero();
    assertThat(count("property_access_approval", "property_id = " + property)).isZero();
    assertThat(count("property", "id = " + property)).isEqualTo(1);
    assertThat(count("customer", "id = " + customer)).isEqualTo(1);
    assertThat(count("contract", "id = " + contract)).isEqualTo(1);
    assertThat(count("office", "id = " + officeId)).isEqualTo(1);
    assertThat(count("office_membership", "office_id = " + officeId)).isPositive();
  }

  @Test
  void 참조하는_계약이_없으면_고객과_매물도_지운다() {
    long customer = visit.base().customer();
    long property = visit.base().propertyId();

    purger.execute(officeId, Instant.now());

    assertThat(count("customer", "id = " + customer)).isZero();
    assertThat(count("property", "id = " + property)).isZero();
    assertThat(count("office", "id = " + officeId)).isEqualTo(1);
    assertThat(purger.dueCount(officeId)).isZero();
  }

  @Test
  void 다른_사무소의_데이터는_건드리지_않는다() throws Exception {
    SelectionFixture other = confirmed();
    long otherOffice = other.base().owner().office().getId();

    purger.execute(officeId, Instant.now());

    assertThat(count("customer", "office_id = " + otherOffice)).isPositive();
    assertThat(count("property", "office_id = " + otherOffice)).isPositive();
    assertThat(count("visit_request", "applicant_office_id = " + otherOffice)).isPositive();
  }

  @Test
  void 지운_행의_파일은_저장소에서도_지운다() {
    long property = visit.base().propertyId();
    objectStorage.put("photos/a", new byte[] {1}, "image/png");
    jdbcTemplate.update(
        "INSERT INTO property_photo (property_id, object_key, sort_order) VALUES (?, 'photos/a',"
            + " 1)",
        property);

    purger.execute(officeId, Instant.now());

    assertThat(count("property_photo", "property_id = " + property)).isZero();
    assertThat(objectStorage.find("photos/a")).isEmpty();
  }

  @Test
  void 파기_큐_계획과_실행은_종료된_사무소만_지우고_결과를_성공으로_돌려준다() {
    closeOffice();
    Plan plan = targetService.plan("OFFICE_DATA", officeId, Instant.now());
    assertThat(plan.supported()).isTrue();
    assertThat(plan.holdReasons()).isEmpty();
    assertThat(plan.targetCount()).isPositive();

    Outcome outcome = targetService.execute("OFFICE_DATA", officeId, plan);

    assertThat(outcome.storageResult()).isEqualTo(DeletionTargetService.RESULT_SUCCESS);
    assertThat(count("customer", "office_id = " + officeId)).isZero();
  }

  @Test
  void 삭제_순서는_모든_외래키에서_참조하는_표가_먼저이거나_연쇄_삭제다() {
    List<String> order = OfficeDataPurger.STEPS.stream().map(OfficeDataPurger.Step::table).toList();
    List<Map<String, Object>> edges =
        jdbcTemplate.queryForList(
            "SELECT cl.relname AS child, pcl.relname AS parent FROM pg_constraint c JOIN pg_class"
                + " cl ON cl.oid = c.conrelid JOIN pg_class pcl ON pcl.oid = c.confrelid JOIN"
                + " pg_namespace n ON n.oid = pcl.relnamespace WHERE c.contype = 'f' AND n.nspname"
                + " = current_schema() AND c.confdeltype IN ('a','r')");
    List<String> violations = new ArrayList<>();
    for (Map<String, Object> edge : edges) {
      String child = (String) edge.get("child");
      String parent = (String) edge.get("parent");
      int childIndex = order.indexOf(child);
      int parentIndex = order.indexOf(parent);
      if (childIndex >= 0 && parentIndex >= 0 && child.equals(parent) == false) {
        boolean allowed =
            childIndex < parentIndex
                || Set.of("visit_schedule_item>visit_request", "visit_request>visit_schedule_item")
                    .contains(child + ">" + parent);
        if (!allowed) {
          violations.add(child + " -> " + parent);
        }
      }
    }
    assertThat(violations.stream().collect(Collectors.toSet())).isEmpty();
  }

  @Test
  void 모든_삭제_문은_빈_사무소에서도_오류_없이_실행된다() {
    long empty = officeId + 100_000;
    assertThat(purger.execute(empty, Instant.now())).isZero();
  }
}
