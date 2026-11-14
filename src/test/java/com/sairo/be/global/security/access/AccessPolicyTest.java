package com.sairo.be.global.security.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffRole;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AccessPolicyTest {

  private static final AccessSubject AGENT = AccessSubject.member(1L, 2L, 3L, StaffRole.AGENT);

  @Test
  void 판정_순서는_문서의_일곱_단계와_같다() {
    AccessPolicy policy = new AccessPolicy(List.of());

    assertThat(policy.order())
        .containsExactly(
            AccessStageType.LOGIN,
            AccessStageType.OFFICE_BOUNDARY,
            AccessStageType.MEMBERSHIP_STATUS,
            AccessStageType.VERIFICATION_STATE,
            AccessStageType.SUBSCRIPTION_STATE,
            AccessStageType.ROLE_PERMISSION,
            AccessStageType.TARGET_DATA_STATE);
  }

  @Test
  void 단계를_거꾸로_등록해도_정해진_순서로_실행한다() {
    List<AccessStageType> executed = new ArrayList<>();
    List<AccessStage> stages = new ArrayList<>();
    for (AccessStageType type : AccessStageType.values()) {
      stages.add(recording(type, executed, AccessStageResult.allow()));
    }
    Collections.reverse(stages);

    new AccessPolicy(stages).check(request(AccessPermission.STAFF, AGENT));

    assertThat(executed).containsExactly(AccessStageType.values());
  }

  @Test
  void 하나라도_불허하면_뒤_단계를_실행하지_않는다() {
    List<AccessStageType> executed = new ArrayList<>();
    List<AccessStage> stages =
        List.of(
            recording(AccessStageType.LOGIN, executed, AccessStageResult.allow()),
            recording(
                AccessStageType.MEMBERSHIP_STATUS,
                executed,
                AccessStageResult.deny(ErrorCode.FORBIDDEN)),
            recording(AccessStageType.SUBSCRIPTION_STATE, executed, AccessStageResult.allow()));

    assertThatThrownBy(() -> new AccessPolicy(stages).check(request(AccessPermission.STAFF, AGENT)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

    assertThat(executed).containsExactly(AccessStageType.LOGIN, AccessStageType.MEMBERSHIP_STATUS);
  }

  @Test
  void 단계별_기본_오류_코드는_문서의_의미에_대응한다() {
    assertThat(AccessStageType.LOGIN.defaultDenial()).isEqualTo(ErrorCode.UNAUTHENTICATED);
    assertThat(AccessStageType.OFFICE_BOUNDARY.defaultDenial())
        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    assertThat(AccessStageType.MEMBERSHIP_STATUS.defaultDenial()).isEqualTo(ErrorCode.FORBIDDEN);
    assertThat(AccessStageType.VERIFICATION_STATE.defaultDenial()).isEqualTo(ErrorCode.FORBIDDEN);
    assertThat(AccessStageType.SUBSCRIPTION_STATE.defaultDenial()).isEqualTo(ErrorCode.FORBIDDEN);
    assertThat(AccessStageType.ROLE_PERMISSION.defaultDenial()).isEqualTo(ErrorCode.FORBIDDEN);
    assertThat(AccessStageType.TARGET_DATA_STATE.defaultDenial())
        .isEqualTo(ErrorCode.INVALID_TRANSITION);
  }

  @ParameterizedTest
  @EnumSource(AccessStageType.class)
  void 단계가_오류_코드를_지정하지_않으면_그_단계의_기본_코드로_거절한다(AccessStageType type) {
    AccessStage denying =
        new AccessStage() {
          @Override
          public AccessStageType type() {
            return type;
          }

          @Override
          public AccessStageResult evaluate(AccessRequest request) {
            return AccessStageResult.deny(null);
          }
        };

    assertThatThrownBy(
            () -> new AccessPolicy(List.of(denying)).check(request(AccessPermission.STAFF, AGENT)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(type.defaultDenial()));
  }

  @Test
  void 같은_종류의_단계를_둘_등록하면_기동을_거절한다() {
    List<AccessStage> stages =
        List.of(
            AccessStage.passThrough(AccessStageType.LOGIN),
            AccessStage.passThrough(AccessStageType.LOGIN));

    assertThatThrownBy(() -> new AccessPolicy(stages)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 공개_권한은_단계를_실행하지_않는다() {
    List<AccessStageType> executed = new ArrayList<>();
    List<AccessStage> stages =
        List.of(
            recording(
                AccessStageType.LOGIN,
                executed,
                AccessStageResult.deny(ErrorCode.UNAUTHENTICATED)));

    new AccessPolicy(stages).check(request(AccessPermission.PUBLIC, AccessSubject.anonymous()));

    assertThat(executed).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = AccessPermission.class,
      names = {"AUTHENTICATED", "STAFF", "AGENT_OR_OWNER", "OWNER"})
  void 로그인하지_않은_요청은_회원_권한_값에서_401이다(AccessPermission permission) {
    assertDenied(permission, AccessSubject.anonymous(), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 권한_값은_열한_개이고_주체_유형은_다섯_가지_이상이다() {
    assertThat(AccessPermission.values()).hasSize(11);
    assertThat(PrincipalType.values())
        .contains(
            PrincipalType.MEMBER,
            PrincipalType.OPERATOR,
            PrincipalType.GUEST_LINK,
            PrincipalType.APPROVER_SESSION,
            PrincipalType.EXTERNAL_WEBHOOK);
  }

  @Test
  void 역할별_허용_범위를_판정한다() {
    AccessSubject owner = AccessSubject.member(1L, 2L, 3L, StaffRole.OWNER);
    AccessSubject agent = AccessSubject.member(1L, 2L, 3L, StaffRole.AGENT);
    AccessSubject deputy = AccessSubject.member(1L, 2L, 3L, StaffRole.DEPUTY);

    assertAllowed(AccessPermission.STAFF, owner, agent, deputy);
    assertAllowed(AccessPermission.AGENT_OR_OWNER, owner, agent);
    assertDenied(AccessPermission.AGENT_OR_OWNER, deputy, ErrorCode.FORBIDDEN);
    assertAllowed(AccessPermission.OWNER, owner);
    assertDenied(AccessPermission.OWNER, agent, ErrorCode.FORBIDDEN);
    assertDenied(AccessPermission.OWNER, deputy, ErrorCode.FORBIDDEN);
  }

  @Test
  void 소속이_없는_회원은_인증만_요구하는_권한_값만_통과한다() {
    AccessSubject noMembership = AccessSubject.member(1L, null, null, null);

    assertAllowed(AccessPermission.AUTHENTICATED, noMembership);
    assertDenied(AccessPermission.STAFF, noMembership, ErrorCode.FORBIDDEN);
    assertDenied(AccessPermission.AGENT_OR_OWNER, noMembership, ErrorCode.FORBIDDEN);
    assertDenied(AccessPermission.OWNER, noMembership, ErrorCode.FORBIDDEN);
  }

  @Test
  void 다른_종류의_세션은_범위가_다른_권한_값에_접근하지_못한다() {
    AccessSubject operator = AccessSubject.of(PrincipalType.OPERATOR);
    AccessSubject approver = AccessSubject.of(PrincipalType.APPROVER_SESSION);
    AccessSubject grant = AccessSubject.of(PrincipalType.GRANT_HOLDER);

    assertDenied(AccessPermission.STAFF, operator, ErrorCode.FORBIDDEN);
    assertDenied(AccessPermission.STAFF, approver, ErrorCode.FORBIDDEN);
    assertDenied(AccessPermission.STAFF, grant, ErrorCode.FORBIDDEN);
    assertDenied(AccessPermission.OPERATOR, AGENT, ErrorCode.FORBIDDEN);
    assertDenied(AccessPermission.APPROVER_SESSION, AGENT, ErrorCode.FORBIDDEN);
    assertDenied(AccessPermission.RETURN_GRANT, AGENT, ErrorCode.RETURN_GRANT_INVALID);
    assertDenied(AccessPermission.ARCHIVE_GRANT, AGENT, ErrorCode.ARCHIVE_GRANT_INVALID);
    assertAllowed(AccessPermission.OPERATOR, operator);
    assertAllowed(AccessPermission.APPROVER_SESSION, approver);
    assertAllowed(AccessPermission.RETURN_GRANT, grant);
    assertAllowed(AccessPermission.ARCHIVE_GRANT, grant);
  }

  @Test
  void 링크와_웹훅_권한_값은_주체가_없으면_각각_링크_없음_웹훅_인증_실패로_응답한다() {
    assertDenied(
        AccessPermission.GUEST_LINK, AccessSubject.anonymous(), ErrorCode.PUBLIC_LINK_NOT_FOUND);
    assertDenied(AccessPermission.GUEST_LINK, AGENT, ErrorCode.PUBLIC_LINK_NOT_FOUND);
    assertDenied(
        AccessPermission.EXTERNAL_WEBHOOK,
        AccessSubject.anonymous(),
        ErrorCode.WEBHOOK_AUTH_FAILED);
    assertAllowed(AccessPermission.GUEST_LINK, AccessSubject.of(PrincipalType.GUEST_LINK));
    assertAllowed(
        AccessPermission.EXTERNAL_WEBHOOK, AccessSubject.of(PrincipalType.EXTERNAL_WEBHOOK));
  }

  @Test
  void 역할은_문서_용어와_대응한다() {
    assertThat(StaffRole.OWNER.legalTitle()).isEqualTo("대표");
    assertThat(StaffRole.AGENT.legalTitle()).isEqualTo("소속공인중개사");
    assertThat(StaffRole.DEPUTY.legalTitle()).isEqualTo("중개보조원");
  }

  @Test
  void 역할_단계는_로그인_단계_뒤에서만_실행되어_익명_요청은_먼저_401을_받는다() {
    List<AccessStageType> executed = new ArrayList<>();
    List<AccessStage> stages =
        List.of(
            new LoginAccessStage(),
            recording(AccessStageType.OFFICE_BOUNDARY, executed, AccessStageResult.allow()));

    assertThatThrownBy(
            () ->
                new AccessPolicy(stages)
                    .check(request(AccessPermission.STAFF, AccessSubject.anonymous())))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    assertThat(executed).isEmpty();
  }

  private void assertAllowed(AccessPermission permission, AccessSubject... subjects) {
    for (AccessSubject subject : subjects) {
      defaultPolicy().check(request(permission, subject));
    }
  }

  private void assertDenied(
      AccessPermission permission, AccessSubject subject, ErrorCode expected) {
    assertThatThrownBy(() -> defaultPolicy().check(request(permission, subject)))
        .as("%s %s", permission, subject)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
  }

  private AccessPolicy defaultPolicy() {
    return new AccessPolicy(
        List.of(new LoginAccessStage(), new MembershipAccessStage(), new RoleAccessStage()));
  }

  private AccessRequest request(AccessPermission permission, AccessSubject subject) {
    return new AccessRequest(permission, subject, "GET", "/api/test");
  }

  private AccessStage recording(
      AccessStageType type, List<AccessStageType> executed, AccessStageResult result) {
    return new AccessStage() {
      @Override
      public AccessStageType type() {
        return type;
      }

      @Override
      public AccessStageResult evaluate(AccessRequest request) {
        executed.add(type);
        return result;
      }
    };
  }
}
