package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.visit.entity.PropertyAccessApproval;
import com.sairo.be.domain.visit.repository.ApproverProfileRepository;
import com.sairo.be.domain.visit.repository.PropertyAccessApprovalRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.support.AdvisoryLock;
import java.time.Clock;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 승인자 세션 경로의 출입승인 판정이다. 프로필이 활성이고 출입승인 연결이 LINKED여야 하며 아니면 404다. 쓰기는 대표 승인자 출입승인만 허용한다. */
@Profile("!migrate")
@Component
public class ApproverPortalAccess {

  public static String profileLockKey(Long profileId) {
    return "approver:profile:" + profileId;
  }

  private final ApproverProfileRepository profileRepository;
  private final PropertyAccessApprovalRepository approvalRepository;
  private final AdvisoryLock advisoryLock;
  private final Clock clock;

  public ApproverPortalAccess(
      ApproverProfileRepository profileRepository,
      PropertyAccessApprovalRepository approvalRepository,
      AdvisoryLock advisoryLock,
      Clock clock) {
    this.profileRepository = profileRepository;
    this.approvalRepository = approvalRepository;
    this.advisoryLock = advisoryLock;
    this.clock = clock;
  }

  public PropertyAccessApproval linked(Long profileId, Long approvalId) {
    if (!profileRepository.isLinked(profileId, approvalId)) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    return approvalRepository
        .findById(approvalId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  public PropertyAccessApproval primary(Long profileId, Long approvalId) {
    PropertyAccessApproval approval = linked(profileId, approvalId);
    if (!approval.isPrimary()) {
      throw new BusinessException(ErrorCode.APPROVER_READ_ONLY);
    }
    return approval;
  }

  /** 출입승인 잠금 키와 행 잠금을 잡은 뒤 연결과 대표 승인자 여부를 다시 확인한다. */
  public PropertyAccessApproval lockForWrite(Long profileId, Long approvalId) {
    linked(profileId, approvalId);
    advisoryLock.lockForTransaction(ApprovalAccess.lockKey(approvalId));
    PropertyAccessApproval approval =
        approvalRepository
            .findForUpdateById(approvalId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!profileRepository.isLinked(profileId, approvalId)) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    if (!approval.isPrimary()) {
      throw new BusinessException(ErrorCode.APPROVER_READ_ONLY);
    }
    return approval;
  }

  /** 공동 승인자 출입승인은 같은 매물의 현재 사용 가능한 대표 승인자 출입승인의 데이터를 읽는다. */
  public Optional<Long> dataApprovalId(PropertyAccessApproval approval) {
    if (approval.isPrimary()) {
      return Optional.of(approval.getId());
    }
    return approvalRepository
        .findUsablePrimaries(approval.getPropertyId(), clock.instant())
        .stream()
        .findFirst()
        .map(PropertyAccessApproval::getId);
  }
}
