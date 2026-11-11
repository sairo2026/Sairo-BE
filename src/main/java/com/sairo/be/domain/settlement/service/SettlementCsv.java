package com.sairo.be.domain.settlement.service;

import com.sairo.be.domain.settlement.dto.response.MonthlySettlementResponse;
import java.util.List;

/** 결산 내보내기 CSV다. 열 순서는 SettlementExportResponse 표와 같고 부가 표시값 네 열이 마지막이다. */
public final class SettlementCsv {

  static final List<String> HEADER =
      List.of(
          "yearMonth",
          "isFinalized",
          "propertyCountTotal",
          "customerCountTotal",
          "propertyCountNew",
          "customerCountNew",
          "firstInquiryCount",
          "visitCompletedCount",
          "visitCompletedPropertyCount",
          "coordinationEndedCount",
          "coordinationVisitCompletedCount",
          "coordinationVisitIncompleteCount",
          "contractSignedCount",
          "contractSignedPropertyCount",
          "leaseMaturityCount",
          "leaseMaturityPropertyCount",
          "agreedFeeTotal",
          "actualFeeReceivedTotal",
          "outstandingAmount",
          "overcollectedAmount",
          "propertyCountNewMigrated",
          "customerCountNewMigrated",
          "agreedFeeUndatedCount",
          "asOf");

  private SettlementCsv() {}

  public static String render(MonthlySettlementResponse r) {
    List<String> values =
        List.of(
            r.yearMonth(),
            String.valueOf(r.isFinalized()),
            String.valueOf(r.propertyCountTotal()),
            String.valueOf(r.customerCountTotal()),
            String.valueOf(r.propertyCountNew()),
            String.valueOf(r.customerCountNew()),
            String.valueOf(r.firstInquiryCount()),
            String.valueOf(r.visitCompletedCount()),
            String.valueOf(r.visitCompletedPropertyCount()),
            String.valueOf(r.coordinationEndedCount()),
            String.valueOf(r.coordinationVisitCompletedCount()),
            String.valueOf(r.coordinationVisitIncompleteCount()),
            String.valueOf(r.contractSignedCount()),
            String.valueOf(r.contractSignedPropertyCount()),
            String.valueOf(r.leaseMaturityCount()),
            String.valueOf(r.leaseMaturityPropertyCount()),
            String.valueOf(r.agreedFeeTotal()),
            String.valueOf(r.actualFeeReceivedTotal()),
            String.valueOf(r.outstandingAmount()),
            String.valueOf(r.overcollectedAmount()),
            String.valueOf(r.propertyCountNewMigrated()),
            String.valueOf(r.customerCountNewMigrated()),
            String.valueOf(r.agreedFeeUndatedCount()),
            r.asOf().toString());
    return String.join(",", HEADER) + "\r\n" + String.join(",", values) + "\r\n";
  }
}
