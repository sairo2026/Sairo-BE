package com.sairo.be.domain.billing.controller;

import com.sairo.be.domain.billing.service.PromotionService;
import com.sairo.be.domain.billing.service.PromotionService.PeriodView;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/billing/promotion-periods")
@RequiresAccess(AccessPermission.OWNER)
@Tag(name = "BILLING", description = "무료 프로모션 내부 이용기간")
public class PromotionPeriodController {
  private final PromotionService service;

  public PromotionPeriodController(PromotionService service) {
    this.service = service;
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
}
