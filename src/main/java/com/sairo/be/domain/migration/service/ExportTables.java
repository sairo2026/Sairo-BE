package com.sairo.be.domain.migration.service;

import java.util.Arrays;
import java.util.List;

public enum ExportTables {
  PROPERTY(
      "매물",
      source(
          "property.csv", "SELECT t.* FROM property t WHERE t.office_id = :officeId ORDER BY t.id"),
      source(
          "property_owner_occupant.csv",
          "SELECT t.* FROM property_owner_occupant t WHERE t.office_id = :officeId ORDER BY t.id"),
      source(
          "property_photo.csv",
          "SELECT t.id, t.property_id, t.description, t.sort_order, t.created_at FROM property_photo t"
              + " JOIN property p ON p.id = t.property_id WHERE p.office_id = :officeId ORDER BY t.id")),
  CUSTOMER(
      "고객",
      source(
          "customer.csv", "SELECT t.* FROM customer t WHERE t.office_id = :officeId ORDER BY t.id"),
      source(
          "customer_consultation.csv",
          "SELECT t.* FROM customer_consultation t JOIN customer c ON c.id = t.customer_id"
              + " WHERE c.office_id = :officeId ORDER BY t.id"),
      source(
          "customer_property_interest.csv",
          "SELECT t.* FROM customer_property_interest t WHERE t.office_id = :officeId ORDER BY t.id"),
      source(
          "customer_demand_condition.csv",
          "SELECT t.* FROM customer_demand_condition t JOIN customer c ON c.id = t.customer_id"
              + " WHERE c.office_id = :officeId ORDER BY t.id")),
  CONTRACT(
      "계약",
      source(
          "contract.csv", "SELECT t.* FROM contract t WHERE t.office_id = :officeId ORDER BY t.id"),
      source(
          "contract_party.csv",
          "SELECT t.* FROM contract_party t JOIN contract c ON c.id = t.contract_id"
              + " WHERE c.office_id = :officeId ORDER BY t.id"),
      source(
          "contract_document.csv",
          "SELECT t.id, t.contract_id, t.contract_revision_id, t.document_type, t.version_no,"
              + " t.supersedes_document_id, t.written_date, t.delivered_date, t.date_pending_reason,"
              + " t.retention_end_date, t.retention_years, t.pii_masking_confirmed,"
              + " t.uploaded_by_membership_id, t.uploaded_at FROM contract_document t"
              + " JOIN contract c ON c.id = t.contract_id WHERE c.office_id = :officeId ORDER BY t.id")),
  FEE(
      "수수료",
      source(
          "contract_fee.csv",
          "SELECT t.* FROM contract_fee t JOIN contract c ON c.id = t.contract_id"
              + " WHERE c.office_id = :officeId ORDER BY t.id"),
      source(
          "contract_fee_transaction.csv",
          "SELECT t.* FROM contract_fee_transaction t JOIN contract_fee f ON f.id = t.fee_id"
              + " JOIN contract c ON c.id = f.contract_id WHERE c.office_id = :officeId ORDER BY t.id")),
  PAYMENT(
      "잔금·지급",
      source(
          "contract_payment_schedule.csv",
          "SELECT t.* FROM contract_payment_schedule t JOIN contract c ON c.id = t.contract_id"
              + " WHERE c.office_id = :officeId ORDER BY t.id"),
      source(
          "contract_payment_transaction.csv",
          "SELECT t.* FROM contract_payment_transaction t"
              + " JOIN contract_payment_schedule s ON s.id = t.schedule_id"
              + " JOIN contract c ON c.id = s.contract_id WHERE c.office_id = :officeId ORDER BY t.id")),
  TASK(
      "업무",
      source("task.csv", "SELECT t.* FROM task t WHERE t.office_id = :officeId ORDER BY t.id"),
      source(
          "contract_followup_task.csv",
          "SELECT t.* FROM contract_followup_task t JOIN contract c ON c.id = t.contract_id"
              + " WHERE c.office_id = :officeId ORDER BY t.id")),
  VISIT(
      "방문",
      source(
          "visit_coordination.csv",
          "SELECT t.* FROM visit_coordination t WHERE t.office_id = :officeId ORDER BY t.id"),
      source(
          "visit_schedule_item.csv",
          "SELECT t.* FROM visit_schedule_item t WHERE t.office_id = :officeId ORDER BY t.id"));

  public record Source(String fileName, String sql) {}

  private final String label;
  private final List<Source> sources;

  ExportTables(String label, Source... sources) {
    this.label = label;
    this.sources = List.of(sources);
  }

  public String label() {
    return label;
  }

  public List<Source> sources() {
    return sources;
  }

  public static boolean isKnown(String code) {
    return Arrays.stream(values()).anyMatch(table -> table.name().equals(code));
  }

  private static Source source(String fileName, String sql) {
    return new Source(fileName, sql);
  }
}
