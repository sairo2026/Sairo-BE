package com.sairo.be.domain.auth.mapper;

import com.sairo.be.domain.auth.dto.response.AccessConditionResponse;
import com.sairo.be.domain.auth.dto.response.AuthContextResponse;
import com.sairo.be.domain.auth.dto.response.CurrentSessionResponse;
import com.sairo.be.domain.auth.dto.response.TermsCatalogResponse;
import com.sairo.be.domain.auth.dto.response.TermsReconsentResponse;
import com.sairo.be.domain.auth.dto.response.UnresolvedRejectionResponse;
import com.sairo.be.domain.auth.service.AccessJudgement;
import com.sairo.be.domain.auth.service.ReconsentService.PendingReconsent;
import com.sairo.be.domain.legal.service.TermsVersionView;
import com.sairo.be.global.security.AbsoluteSessionTimeoutFilter;
import com.sairo.be.global.security.SessionKind;
import com.sairo.be.global.support.TimeRules;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public final class AuthMapper {

  private AuthMapper() {}

  public static AccessConditionResponse toAccessCondition(AccessJudgement judgement) {
    return new AccessConditionResponse(
        judgement.accessCondition().name(),
        judgement.officeId(),
        judgement.role(),
        judgement.pendingRegistrationId(),
        judgement.pendingMembershipId(),
        rejection(judgement),
        display(judgement.graceUntil()),
        display(judgement.returnPeriodEndsAt()),
        judgement.restrictionReason(),
        judgement.removalReason());
  }

  public static AuthContextResponse toAuthContext(
      AccessJudgement judgement, boolean reconsentRequired) {
    return new AuthContextResponse(
        judgement.accessCondition().name(),
        judgement.officeId(),
        judgement.role(),
        judgement.pendingRegistrationId(),
        judgement.pendingMembershipId(),
        rejection(judgement),
        display(judgement.graceUntil()),
        display(judgement.returnPeriodEndsAt()),
        judgement.restrictionReason(),
        judgement.removalReason(),
        reconsentRequired);
  }

  public static TermsCatalogResponse toCatalog(
      List<TermsVersionView> views, boolean phoneVerificationRequired) {
    return new TermsCatalogResponse(
        views.stream()
            .map(
                view ->
                    new TermsCatalogResponse.Item(
                        view.termsCode(),
                        view.version(),
                        view.required(),
                        view.title(),
                        view.documentUrl(),
                        view.category().name(),
                        view.reconsentRequired(),
                        view.changeSummary()))
            .toList(),
        phoneVerificationRequired);
  }

  public static TermsReconsentResponse toReconsent(List<PendingReconsent> pending) {
    return new TermsReconsentResponse(
        !pending.isEmpty(),
        pending.stream()
            .map(
                item ->
                    new TermsReconsentResponse.Item(
                        item.current().termsCode(),
                        item.previousVersion(),
                        item.current().version(),
                        item.current().category().name(),
                        item.current().required(),
                        display(item.current().effectiveAt()),
                        item.current().changeSummary(),
                        item.current().documentUrl(),
                        item.previousDocumentUrl()))
            .toList());
  }

  public static CurrentSessionResponse toCurrentSession(
      HttpSession session, Optional<Instant> stepUpUntil) {
    Instant authenticatedAt =
        session.getAttribute(AbsoluteSessionTimeoutFilter.ISSUED_AT_ATTRIBUTE) instanceof Instant at
            ? at
            : Instant.ofEpochMilli(session.getCreationTime());
    Instant lastAccessedAt = Instant.ofEpochMilli(session.getLastAccessedTime());
    return new CurrentSessionResponse(
        display(authenticatedAt),
        display(lastAccessedAt),
        display(lastAccessedAt.plus(SessionKind.MEMBER.idleTimeout())),
        display(authenticatedAt.plus(SessionKind.MEMBER.absoluteTimeout())),
        stepUpUntil.isPresent(),
        stepUpUntil.map(AuthMapper::display).orElse(null));
  }

  private static UnresolvedRejectionResponse rejection(AccessJudgement judgement) {
    return judgement.unresolvedRejection() == null
        ? null
        : new UnresolvedRejectionResponse(
            judgement.unresolvedRejection().type(), judgement.unresolvedRejection().reason());
  }

  private static OffsetDateTime display(Instant instant) {
    return instant == null ? null : TimeRules.toDisplay(instant).toOffsetDateTime();
  }
}
