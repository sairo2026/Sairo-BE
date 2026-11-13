package com.sairo.be.domain.legal.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class OfficeDataPurgeScanService {

  public static final String OFFICE_DATA_TARGET = "OFFICE_DATA";
  public static final String REASON = "사무소 종료 후 반환 기간 만료";

  private final JdbcClient jdbcClient;
  private final Clock clock;

  public OfficeDataPurgeScanService(JdbcClient jdbcClient, Clock clock) {
    this.jdbcClient = jdbcClient;
    this.clock = clock;
  }

  @Transactional
  public int scan() {
    Instant now = clock.instant();
    List<Long> offices =
        jdbcClient
            .sql(
                "SELECT o.id FROM office o WHERE o.closed_at IS NOT NULL AND NOT EXISTS (SELECT 1"
                    + " FROM return_grant g WHERE g.office_id = o.id AND g.revoked_at IS NULL AND"
                    + " g.expires_at > :now) AND NOT EXISTS (SELECT 1 FROM deletion_queue q WHERE"
                    + " q.target_type = :type AND q.target_id = o.id AND q.cancelled_at IS NULL)"
                    + " ORDER BY o.id")
            .param("now", Timestamp.from(now))
            .param("type", OFFICE_DATA_TARGET)
            .query(Long.class)
            .list();
    for (Long officeId : offices) {
      jdbcClient
          .sql(
              "INSERT INTO deletion_queue (target_type, target_id, office_id, reason,"
                  + " scheduled_at, target_count) VALUES (:type, :office, :office, :reason, :at,"
                  + " (SELECT (SELECT count(*) FROM property WHERE office_id = :office) + (SELECT"
                  + " count(*) FROM customer WHERE office_id = :office) + (SELECT count(*) FROM"
                  + " contract WHERE office_id = :office)))")
          .param("type", OFFICE_DATA_TARGET)
          .param("office", officeId)
          .param("reason", REASON)
          .param("at", Timestamp.from(now))
          .update();
    }
    return offices.size();
  }
}
