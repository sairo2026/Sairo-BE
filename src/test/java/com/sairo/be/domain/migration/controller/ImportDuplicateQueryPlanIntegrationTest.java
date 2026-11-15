package com.sairo.be.domain.migration.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.customer.repository.CustomerImportRepository;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.support.ImportIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class ImportDuplicateQueryPlanIntegrationTest extends ImportIntegrationTestBase {

  @Autowired private CustomerImportRepository customerImportRepository;

  @Test
  void 기존_고객_조회는_이름_키_인덱스를_쓰고_500개_키를_한_번에_조회한다() {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    long officeId = owner.office().getId();
    jdbcTemplate.update(
        "INSERT INTO customer (office_id, name, managing_membership_id, data_source,"
            + " data_purpose, retention_until, phone) SELECT ?, '고객' || g, ?, '직접 입력', '상담',"
            + " now() + interval '1 year', '010-0000-' || lpad((g % 10000)::text, 4, '0')"
            + " FROM generate_series(1, 30000) g",
        officeId, owner.membership().getId());
    jdbcTemplate.execute("ANALYZE customer");
    List<String> names = new ArrayList<>();
    for (int i = 1; i <= 500; i++) {
      names.add("고객" + (i * 37));
    }

    List<CustomerImportRepository.ExistingMatchRow> rows =
        customerImportRepository.findExistingMatches(officeId, names);

    assertThat(rows).hasSize(500);
    List<String> plan =
        jdbcTemplate.queryForList(
            "EXPLAIN SELECT c.id FROM customer c WHERE c.office_id = ? AND"
                + " lower(regexp_replace(c.name, '\\s+', '', 'g')) IN ('고객37', '고객74', '고객111')",
            String.class,
            officeId);
    assertThat(String.join("\n", plan)).contains("ix_customer_office_name_key");
  }
}
