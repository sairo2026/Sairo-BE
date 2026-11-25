package com.sairo.be.domain.billing.controller;

import com.sairo.be.domain.billing.service.PromotionService;
import com.sairo.be.domain.billing.service.PromotionService.PeriodView;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.RateLimitRule;
import com.sairo.be.global.support.RequestRateLimiter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/billing/promotion-periods")
@RequiresAccess(AccessPermission.OWNER)
@Tag(name = "BILLING", description = "무료 프로모션 내부 이용기간")
public class PromotionPeriodController {
  private static final RateLimitRule REDEMPTION =
      RateLimitRule.of("promotion-code-redemption-office", 5, Duration.ofHours(1));
  private final PromotionService service;
  private final RequestRateLimiter rateLimiter;

  public PromotionPeriodController(PromotionService service, RequestRateLimiter rateLimiter) {
    this.service = service;
    this.rateLimiter = rateLimiter;
  }

  @GetMapping
  public List<PeriodView> mine(@AuthenticationPrincipal StaffPrincipal principal) {
    return service.periodsForOffice(principal.officeId());
  }

  @GetMapping("/current")
  public Optional<PromotionService.CurrentPromotion> current(
      @AuthenticationPrincipal StaffPrincipal principal) {
    return service.currentForOffice(principal.officeId());
  }

  @PostMapping("/redeem")
  @ResponseStatus(HttpStatus.CREATED)
  public IdResponse redeem(
      @Valid @RequestBody RedeemRequest body, @AuthenticationPrincipal StaffPrincipal principal) {
    rateLimiter.hit(REDEMPTION, String.valueOf(principal.officeId()));
    return new IdResponse(
        service.redeemCode(principal.membershipId(), principal.officeId(), body.code()));
  }

  public record RedeemRequest(@NotBlank String code) {}

  public record IdResponse(long id) {}
}
