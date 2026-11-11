package com.sairo.be.domain.contract.service;

import com.sairo.be.domain.contract.dto.response.PartyResponse;
import com.sairo.be.domain.contract.entity.ContractRow;
import com.sairo.be.domain.contract.entity.ContractStatus;
import com.sairo.be.domain.contract.entity.ContractValues;
import com.sairo.be.domain.contract.entity.LeasePeriodStatus;
import com.sairo.be.domain.contract.entity.PartyInput;
import com.sairo.be.domain.contract.entity.PartyRow;
import com.sairo.be.domain.contract.entity.PartySide;
import com.sairo.be.domain.contract.repository.ContractFollowupRepository;
import com.sairo.be.domain.contract.repository.ContractHistoryRepository;
import com.sairo.be.domain.contract.repository.ContractPartyRepository;
import com.sairo.be.domain.contract.repository.ContractRepository;
import com.sairo.be.domain.contract.repository.ContractRepository.PropertyRef;
import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.port.ContractWorkHooks;
import com.sairo.be.global.port.SettlementRecalculator;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 계약 명령 서비스들이 함께 쓰는 검사·기록 도구다. */
@Profile("!migrate")
@Component
class ContractSupport {

  static final String TRACK_CONTRACT = "CONTRACT";
  static final String TRACK_LEASE = "LEASE_PERIOD";
  static final String TRACK_SETTLEMENT = "SETTLEMENT";

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final ContractRepository contracts;
  private final ContractPartyRepository parties;
  private final ContractHistoryRepository histories;
  private final ContractFollowupRepository followups;
  private final SettlementRecalculator recalculator;
  private final ContractWorkHooks workHooks;
  private final Clock clock;

  ContractSupport(
      ContractRepository contracts,
      ContractPartyRepository parties,
      ContractHistoryRepository histories,
      ContractFollowupRepository followups,
      SettlementRecalculator recalculator,
      ContractWorkHooks workHooks,
      Clock clock) {
    this.contracts = contracts;
    this.parties = parties;
    this.histories = histories;
    this.followups = followups;
    this.recalculator = recalculator;
    this.workHooks = workHooks;
    this.clock = clock;
  }

  static final long D90_WINDOW_DAYS = 90;

  /**
   * 체결·재계약·정정·상태 변경 뒤 후속업무와 자동 업무를 맞춘다. 체결된 전세·월세의 만기가 오늘부터 90일 이하이면 D-90 후속업무를 바로 만들고, 체결 상태인데 그
   * 조건에서 벗어나면 아직 제출하지 않은 D-90 후속업무를 지운다. 잔금 예정·임대 만기 업무는 홈 업무 포트로 넘긴다.
   */
  void syncFollowups(Long officeId, Long contractId, Instant now) {
    ContractRow row = contracts.find(officeId, contractId).orElseThrow();
    if (row.contractStatus() == ContractStatus.SIGNED) {
      LocalDate end = row.values().leaseEndDate();
      boolean due =
          row.dealType().isLease()
              && row.duplicateExcludedAt() == null
              && end != null
              && !end.isAfter(today(now).plusDays(D90_WINDOW_DAYS));
      if (!due) {
        followups.deleteUnsubmitted(contractId, ContractFollowupRepository.D90);
      } else if (!followups.hasType(contractId, ContractFollowupRepository.D90)) {
        followups.insert(contractId, ContractFollowupRepository.D90, now);
      }
    }
    workHooks.contractChanged(contractId);
  }

  Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  static LocalDate today(Instant now) {
    return TimeRules.displayDate(now);
  }

  ContractRow lock(Long officeId, Long contractId) {
    return contracts
        .lock(officeId, contractId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  static void requireVersion(ContractRow row, long expected) {
    if (row.version() != expected) {
      throw BusinessException.versionConflict(row.version());
    }
  }

  static void requireDraft(ContractRow row) {
    if (!row.isDraft()) {
      throw new BusinessException(ErrorCode.CONTRACT_ACTION_NOT_ALLOWED);
    }
  }

  static void requireSignedOnce(ContractRow row) {
    if (row.isDraft()) {
      throw new BusinessException(ErrorCode.CONTRACT_ACTION_NOT_ALLOWED);
    }
  }

  /** 매물을 잠그고 보관 매물이면 409 PROPERTY_ARCHIVED다. 다른 사무소 매물은 404다. */
  PropertyRef lockProperty(Long officeId, Long propertyId) {
    PropertyRef property =
        contracts
            .lockProperty(officeId, propertyId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (property.archived()) {
      throw new BusinessException(ErrorCode.PROPERTY_ARCHIVED);
    }
    return property;
  }

  void requireManager(Long officeId, Long membershipId) {
    if (!contracts.isContractManager(officeId, membershipId)) {
      throw CustomerInput.invalid(
          "managingMembershipId", FieldErrorCode.INVALID_VALUE, "같은 사무소의 승인된 대표 또는 소속공인중개사여야 합니다.");
    }
  }

  void requireNoConflict(Long propertyId, Long contractId, ContractValues values) {
    if (contracts.hasActiveConflict(
        propertyId,
        contractId,
        values.dealType(),
        values.leaseStartDate(),
        values.leaseEndDate())) {
      throw new BusinessException(ErrorCode.PROPERTY_HAS_ACTIVE_CONTRACT);
    }
  }

  /** 지정한 고객이 같은 사무소 고객인지 보고 병합된 고객이면 최종 고객 ID로 바꾼다. */
  Long resolveCustomer(Long officeId, Long customerId, String field) {
    if (customerId == null) {
      return null;
    }
    return contracts
        .finalCustomerInOffice(officeId, customerId)
        .orElseThrow(
            () -> CustomerInput.invalid(field, FieldErrorCode.INVALID_VALUE, "같은 사무소 고객이 아닙니다."));
  }

  /** 새 당사자 배열을 순서대로 넣는다. 배열 순서가 sequence_no다. */
  void insertParties(
      Long officeId, Long contractId, PartySide side, List<PartyInput> inputs, Instant now) {
    if (inputs == null) {
      return;
    }
    for (int i = 0; i < inputs.size(); i++) {
      PartyInput input = inputs.get(i);
      Long customerId =
          resolveCustomer(officeId, input.customerId(), side.field() + "[" + i + "].customerId");
      parties.insert(contractId, side, i + 1, input.name(), input.phone(), customerId, now);
    }
  }

  static LeasePeriodStatus leaseStatus(ContractValues values, LocalDate today) {
    if (!values.dealType().isLease()
        || values.leaseStartDate() == null
        || values.leaseEndDate() == null) {
      return null;
    }
    return LeasePeriodStatus.of(values.leaseStartDate(), values.leaseEndDate(), today);
  }

  static Instant signedEffectiveAt(LocalDate signedDate) {
    return TimeRules.startOfDisplayDay(signedDate);
  }

  /** 체결 기록이다. 계약 트랙 SIGNED 사건(효력시각은 체결일 0시), 임대기간 사건, 1번 버전을 남기고 같은 거래유형의 활성 거래제안을 완료로 바꾼다. */
  void recordSigning(ContractRow row, String fromStatus, Long actorMembershipId, Instant now) {
    ContractValues values = row.values();
    histories.insertStatus(
        row.id(),
        TRACK_CONTRACT,
        fromStatus,
        "SIGNED",
        null,
        actorMembershipId,
        signedEffectiveAt(values.signedDate()),
        null,
        now);
    if (row.leasePeriodStatus() != null) {
      histories.insertStatus(
          row.id(),
          TRACK_LEASE,
          null,
          row.leasePeriodStatus().name(),
          null,
          actorMembershipId,
          now,
          null,
          now);
    }
    histories.insertRevision(
        row.id(),
        1,
        "SIGNED",
        null,
        snapshot(values, parties.current(row.id())),
        signedEffectiveAt(values.signedDate()),
        actorMembershipId,
        null,
        null,
        now);
    contracts.completeOffers(row.propertyId(), values.dealType(), now);
    recalculate(row.officeId(), affectedTimes(values));
    syncFollowups(row.officeId(), row.id(), now);
  }

  void recalculate(Long officeId, Collection<Instant> times) {
    recalculator.recalculate(officeId, times);
  }

  /** 계약 값이 결산에 영향을 주는 시각이다. 체결일·임대 만기일·잔금 예정일의 0시(Asia/Seoul)다. */
  static List<Instant> affectedTimes(ContractValues values) {
    List<Instant> times = new ArrayList<>();
    for (LocalDate date :
        new LocalDate[] {values.signedDate(), values.leaseEndDate(), values.balanceDueDate()}) {
      if (date != null) {
        times.add(TimeRules.startOfDisplayDay(date));
      }
    }
    return times;
  }

  /**
   * 누적 지표(지급의무자별 미수금·초과수납)에 영향을 주는 날짜부터 이번 달까지 각 달의 첫날 0시다. 보수 사건이나 거래가 과거 날짜로 바뀌면 그 달부터 저장된 결산을 모두
   * 다시 계산해야 하기 때문이다. 현재 달과 저장값이 없는 달은 재계산기가 건너뛴다.
   */
  static List<Instant> monthsFrom(LocalDate from, Instant now) {
    List<Instant> times = new ArrayList<>();
    if (from == null) {
      return times;
    }
    LocalDate month = from.withDayOfMonth(1);
    LocalDate last = today(now).withDayOfMonth(1);
    while (!month.isAfter(last)) {
      times.add(TimeRules.startOfDisplayDay(month));
      month = month.plusMonths(1);
    }
    return times;
  }

  static String snapshot(ContractValues values, List<PartyRow> current) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("signedDate", text(values.signedDate()));
    map.put("dealType", values.dealType().name());
    map.put("salePrice", values.salePrice());
    map.put("depositAmount", values.depositAmount());
    map.put("monthlyRentAmount", values.monthlyRentAmount());
    map.put("balanceDueDate", text(values.balanceDueDate()));
    map.put("balanceDueDatePendingReason", values.balanceDueDatePendingReason());
    map.put("leaseStartDate", text(values.leaseStartDate()));
    map.put("leaseEndDate", text(values.leaseEndDate()));
    map.put("sellerLandlordParties", partyMaps(current, PartySide.SELLER_LANDLORD));
    map.put("buyerTenantParties", partyMaps(current, PartySide.BUYER_TENANT));
    return MAPPER.writeValueAsString(map);
  }

  static JsonNode json(String text) {
    return text == null ? null : MAPPER.readTree(text);
  }

  private static List<Map<String, Object>> partyMaps(List<PartyRow> rows, PartySide side) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (PartyRow row : rows) {
      if (row.side() == side) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("partyId", row.id());
        map.put("sequenceNo", row.sequenceNo());
        map.put("name", row.name());
        map.put("phone", row.phone());
        map.put("customerId", row.customerId());
        result.add(map);
      }
    }
    return result;
  }

  static List<PartyResponse> partyResponses(List<PartyRow> rows, PartySide side) {
    return rows.stream()
        .filter(row -> row.side() == side)
        .map(
            row ->
                new PartyResponse(
                    row.id(), row.sequenceNo(), row.name(), row.phone(), row.customerId()))
        .toList();
  }

  static long count(List<PartyRow> rows, PartySide side) {
    return rows.stream().filter(row -> row.side() == side).count();
  }

  private static String text(LocalDate date) {
    return date == null ? null : date.toString();
  }
}
