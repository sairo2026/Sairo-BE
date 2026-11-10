package com.sairo.be.domain.billing.controller;

import com.sairo.be.domain.billing.dto.response.PublicPricingResponse;
import com.sairo.be.domain.billing.service.PublicPricingService;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.web.ClientRequestInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/public")
@Tag(name = "BILLING", description = "요금·구독·결제")
public class PublicPricingController {

  private static final CacheControl CACHE =
      CacheControl.maxAge(Duration.ofSeconds(300)).cachePublic();

  private final PublicPricingService publicPricingService;

  public PublicPricingController(PublicPricingService publicPricingService) {
    this.publicPricingService = publicPricingService;
  }

  @Operation(
      summary = "공개 요금 조회",
      description = "로그인 없이 시행 중인 표준 요금표를 돌려준다. 시작 고객 가격과 사무소별 가격은 주지 않는다.")
  @GetMapping("/pricing")
  @RequiresAccess(AccessPermission.PUBLIC)
  public ResponseEntity<PublicPricingResponse> pricing(HttpServletRequest request) {
    PublicPricingResponse body =
        publicPricingService.currentPricing(ClientRequestInfo.from(request).ipAddress());
    return ResponseEntity.ok().cacheControl(CACHE).body(body);
  }
}
