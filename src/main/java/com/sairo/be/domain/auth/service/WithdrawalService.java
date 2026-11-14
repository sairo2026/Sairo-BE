package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.dto.response.WithdrawalPreviewResponse;
import com.sairo.be.domain.auth.dto.response.WithdrawalPreviewResponse.RetainedItem;
import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.domain.legal.service.DeletionQueueService;
import com.sairo.be.domain.legal.service.KakaoUnlinkEventService;
import com.sairo.be.domain.legal.service.WithdrawalRetentionService;
import com.sairo.be.domain.office.service.OfficeWithdrawalService;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.SessionTerminator;
import com.sairo.be.global.security.StepUpState;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class WithdrawalService {

  public static final List<String> DESTROYED_ITEMS =
      List.of("이메일", "휴대폰 번호", "표시 이름", "카카오 연결 정보", "알림 설정");
  public static final String DISPLAY_RULE_NOTICE = "담당 업무는 대표님에게 넘어가고 과거 담당 기록에는 탈퇴한 구성원으로 표시됩니다.";

  private final AppUserRepository appUserRepository;
  private final OfficeWithdrawalService officeWithdrawalService;
  private final WithdrawalRetentionService retentionService;
  private final DeletionQueueService deletionQueueService;
  private final KakaoUnlinkEventService kakaoUnlinkEventService;
  private final SessionTerminator sessionTerminator;
  private final StepUpState stepUpState;
  private final Clock clock;

  public WithdrawalService(
      AppUserRepository appUserRepository,
      OfficeWithdrawalService officeWithdrawalService,
      WithdrawalRetentionService retentionService,
      DeletionQueueService deletionQueueService,
      KakaoUnlinkEventService kakaoUnlinkEventService,
      SessionTerminator sessionTerminator,
      StepUpState stepUpState,
      Clock clock) {
    this.appUserRepository = appUserRepository;
    this.officeWithdrawalService = officeWithdrawalService;
    this.retentionService = retentionService;
    this.deletionQueueService = deletionQueueService;
    this.kakaoUnlinkEventService = kakaoUnlinkEventService;
    this.sessionTerminator = sessionTerminator;
    this.stepUpState = stepUpState;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public WithdrawalPreviewResponse preview(Long userId) {
    AppUser user = activeUser(userId);
    boolean owner = officeWithdrawalService.isOwner(userId);
    List<RetainedItem> retained =
        retentionService.retainedItems().stream()
            .map(
                item ->
                    new RetainedItem(
                        item.itemCode(),
                        item.itemName(),
                        item.reason(),
                        item.reviewTargetLaws(),
                        item.retentionDays(),
                        item.anchorEvent(),
                        item.ruleStatus()))
            .toList();
    return new WithdrawalPreviewResponse(
        owner, owner, user.hasKakaoLogin(), DESTROYED_ITEMS, retained, DISPLAY_RULE_NOTICE);
  }

  @Transactional
  public void withdraw(Long userId, HttpSession session) {
    stepUpState.require(session);
    AppUser user = activeUser(userId);
    if (officeWithdrawalService.isOwner(userId)) {
      throw new BusinessException(ErrorCode.LAST_OWNER);
    }
    var now = clock.instant();
    officeWithdrawalService.releaseForWithdrawal(userId, now);
    if (user.hasKakaoLogin()) {
      kakaoUnlinkEventService.enqueueOutbound(userId, user.getKakaoProviderKey());
    }
    user.withdraw(now);
    deletionQueueService.enqueueMemberDeletion(userId);
    sessionTerminator.terminateAll(userId);
  }

  private AppUser activeUser(Long userId) {
    return appUserRepository
        .findById(userId)
        .filter(AppUser::isActive)
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
  }
}
