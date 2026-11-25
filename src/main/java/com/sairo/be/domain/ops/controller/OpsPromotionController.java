package com.sairo.be.domain.ops.controller;

import com.sairo.be.domain.billing.service.PromotionService;
import com.sairo.be.domain.billing.service.PromotionService.CampaignView;
import com.sairo.be.domain.billing.service.PromotionService.GrantView;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.security.access.RequiresOperatorRole;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/ops/promotions")
@RequiresAccess(AccessPermission.OPERATOR)
@RequiresOperatorRole(OperatorRole.BILLING)
@Tag(name = "OPS", description = "0원 내부 프로모션 관리")
public class OpsPromotionController {
  private final PromotionService service;

  public OpsPromotionController(PromotionService service) {
    this.service = service;
  }

  @GetMapping("/campaigns")
  public List<CampaignView> campaigns() {
    return service.campaigns();
  }

  @PostMapping("/campaigns")
  @ResponseStatus(HttpStatus.CREATED)
  public IdResponse create(
      @Valid @RequestBody CampaignRequest body,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    long id =
        service.createCampaign(
            principal.operatorId(),
            body.name(),
            body.code(),
            body.applicationStartsAt().toInstant(),
            body.applicationEndsAt().toInstant(),
            body.freeMonths(),
            body.maxOffices());
    return new IdResponse(id);
  }

  @PostMapping("/campaigns/{id}/activate")
  public void activate(
      @PathVariable long id,
      @AuthenticationPrincipal OperatorPrincipal principal,
      HttpSession session) {
    service.activateCampaign(principal.operatorId(), id, session);
  }

  @PostMapping("/campaigns/{id}/end")
  public void end(
      @PathVariable long id,
      @AuthenticationPrincipal OperatorPrincipal principal,
      HttpSession session) {
    service.endCampaign(principal.operatorId(), id, session);
  }

  @GetMapping("/grants")
  public List<GrantView> grants() {
    return service.grants();
  }

  @PostMapping("/campaigns/{id}/grants")
  @ResponseStatus(HttpStatus.CREATED)
  public IdResponse grant(
      @PathVariable long id,
      @Valid @RequestBody GrantRequest body,
      @AuthenticationPrincipal OperatorPrincipal principal,
      HttpSession session) {
    return new IdResponse(service.grant(principal.operatorId(), id, body.officeId(), session));
  }

  @PostMapping("/grants/{id}/revoke")
  public void revoke(
      @PathVariable long id,
      @AuthenticationPrincipal OperatorPrincipal principal,
      HttpSession session) {
    service.revoke(principal.operatorId(), id, session);
  }

  @PostMapping("/grants/{id}/reconcile-period")
  public void reconcilePeriod(
      @PathVariable long id,
      @Valid @RequestBody ReconcilePeriodRequest body,
      @AuthenticationPrincipal OperatorPrincipal principal,
      HttpSession session) {
    service.reconcilePeriod(
        principal.operatorId(), id, body.historicalMembers(), body.reason(), session);
  }

  public record CampaignRequest(
      @NotBlank String name,
      @NotBlank String code,
      @NotNull OffsetDateTime applicationStartsAt,
      @NotNull OffsetDateTime applicationEndsAt,
      @Min(1) @Max(120) int freeMonths,
      @Min(1) Integer maxOffices) {}

  public record GrantRequest(@NotNull Long officeId) {}

  public record ReconcilePeriodRequest(@Min(1) int historicalMembers, @NotBlank String reason) {}

  public record IdResponse(long id) {}
}
