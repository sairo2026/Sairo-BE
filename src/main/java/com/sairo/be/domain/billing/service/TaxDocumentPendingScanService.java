package com.sairo.be.domain.billing.service;

import com.sairo.be.global.support.TimeRules;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 세금 증빙 발급 대기 기록을 만든다. 현재 세금계산서 수신 정보가 발급 요청이고 결제가 성공한 청구서 가운데 세금계산서 기록이 하나도 없는 청구서마다 발급 대기 행을 만든다.
 * 이미 어떤 상태로든 기록이 있으면 만들지 않으므로 다시 돌려도 같은 결과다.
 */
@Slf4j
@Profile("!migrate")
@Service
public class TaxDocumentPendingScanService {

  public static final String JOB_NAME = "tax-document-pending-scan";

  private final JdbcClient jdbcClient;
  private final SystemJobRunService jobRunService;

  public TaxDocumentPendingScanService(JdbcClient jdbcClient, SystemJobRunService jobRunService) {
    this.jdbcClient = jdbcClient;
    this.jobRunService = jobRunService;
  }

  public boolean runScheduled(Instant now) {
    LocalDate day = TimeRules.displayDate(now);
    return jobRunService.runOnce(
        JOB_NAME,
        null,
        JOB_NAME + ":" + day,
        TimeRules.startOfDisplayDay(day).plus(Duration.ofHours(9)),
        () -> log.info("세금 증빙 발급 대기 생성: {}건", scan(now)));
  }

  @Transactional
  public int scan(Instant now) {
    return jdbcClient
        .sql(
            """
            INSERT INTO tax_document (billing_invoice_id, recipient_id, document_kind,
              is_electronic, status, recorded_by_operator_id, recorded_at, updated_at)
            SELECT i.id, r.id, 'TAX_INVOICE', true, 'PENDING', NULL, :now, :now
              FROM billing_invoice i
              JOIN tax_invoice_recipient r
                ON r.office_id = i.office_id AND r.status = 'ACTIVE' AND r.issue_requested
             WHERE i.status IN ('PAID', 'PARTIALLY_REFUNDED')
               AND NOT EXISTS (SELECT 1 FROM tax_document d
                                WHERE d.billing_invoice_id = i.id
                                  AND d.document_kind = 'TAX_INVOICE')
             ORDER BY i.id
            """)
        .param("now", Timestamp.from(now))
        .update();
  }
}
