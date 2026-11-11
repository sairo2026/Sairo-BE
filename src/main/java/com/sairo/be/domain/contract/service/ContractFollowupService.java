package com.sairo.be.domain.contract.service;

import com.sairo.be.domain.contract.dto.response.ContractFollowupTaskResponse;
import com.sairo.be.domain.contract.entity.ContractRow;
import com.sairo.be.domain.contract.entity.ContractStatus;
import com.sairo.be.domain.contract.repository.ContractFollowupRepository;
import com.sairo.be.domain.contract.repository.ContractFollowupRepository.FollowupRow;
import com.sairo.be.domain.contract.repository.ContractRepository;
import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.TimeRules;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 계약 후속업무 조회·제출 처리와 D-90 매일 배치다(CONTRACT-08). 자동 신고는 하지 않고 제출 여부만 기록한다. 잔금·만기 업무는 홈 업무(HOME-02)로
 * 만든다.
 */
@Profile("!migrate")
@Service
public class ContractFollowupService {

  private static final Set<String> UPDATE_KEYS = Set.of("submitted");

  private final ContractRepository contracts;
  private final ContractFollowupRepository followups;
  private final ContractSupport support;
  private final TransactionTemplate transactionTemplate;

  public ContractFollowupService(
      ContractRepository contracts,
      ContractFollowupRepository followups,
      ContractSupport support,
      TransactionTemplate transactionTemplate) {
    this.contracts = contracts;
    this.followups = followups;
    this.support = support;
    this.transactionTemplate = transactionTemplate;
  }

  @Transactional(readOnly = true)
  public ContractFollowupTaskResponse.FollowupList list(StaffPrincipal principal, Long contractId) {
    ContractRow row =
        contracts
            .find(principal.officeId(), contractId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    return new ContractFollowupTaskResponse.FollowupList(
        followups.list(contractId).stream()
            .map(
                f ->
                    new ContractFollowupTaskResponse.FollowupItem(
                        f.id(),
                        f.taskType(),
                        f.reportTarget(),
                        f.reportBasis(),
                        f.targetDate(),
                        f.submitted(),
                        TimeRules.toDisplayOffset(f.submittedAt()),
                        row.officeId(),
                        row.version()))
            .toList());
  }

  /** 제출 처리다. 이미 제출한 업무에 다시 true를 보내면 바꾸지 않고 현재 값을 돌려준다. */
  @Transactional
  public ContractFollowupTaskResponse submit(
      StaffPrincipal principal, Long taskId, long expectedVersion, JsonNode body) {
    Instant now = support.now();
    FollowupRow task =
        followups
            .lock(principal.officeId(), taskId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    CustomerInput.requireObject(body, UPDATE_KEYS);
    if (!CustomerInput.requiredBoolean(body, "submitted")) {
      throw CustomerInput.invalid(
          "submitted", FieldErrorCode.NOT_ALLOWED, "제출 처리는 true만 보낼 수 있습니다.");
    }
    ContractRow row = support.lock(principal.officeId(), task.contractId());
    if (task.submitted()) {
      return response(task.id(), task.submittedAt(), row);
    }
    ContractSupport.requireVersion(row, expectedVersion);
    followups.submit(taskId, now);
    contracts.bumpVersion(row.id(), now);
    ContractRow updated = contracts.find(principal.officeId(), row.id()).orElseThrow();
    return response(taskId, now, updated);
  }

  private static ContractFollowupTaskResponse response(
      Long taskId, Instant submittedAt, ContractRow row) {
    return new ContractFollowupTaskResponse(
        taskId, true, TimeRules.toDisplayOffset(submittedAt), row.officeId(), row.version());
  }

  /**
   * D-90 매일 배치다. 체결된 전세·월세 가운데 만기가 오늘부터 90일 이하인데 후속업무나 만기 업무가 없는 계약을 찾아 계약마다 따로 커밋해 만든다. 실패한 계약은 다음
   * 날 다시 평가한다. 처리한 계약 수를 돌려준다.
   */
  public int createDue() {
    Instant now = support.now();
    LocalDate limit = ContractSupport.today(now).plusDays(ContractSupport.D90_WINDOW_DAYS);
    int done = 0;
    for (Long id : followups.d90Targets(limit)) {
      Boolean created =
          transactionTemplate.execute(
              status -> {
                Optional<ContractRow> locked = contracts.lockById(id);
                if (locked.isEmpty() || locked.get().contractStatus() != ContractStatus.SIGNED) {
                  return false;
                }
                support.syncFollowups(locked.get().officeId(), id, now);
                return true;
              });
      if (Boolean.TRUE.equals(created)) {
        done++;
      }
    }
    return done;
  }
}
