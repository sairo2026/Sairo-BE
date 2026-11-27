package com.sairo.be.domain.visit.controller;

import com.sairo.be.domain.visit.service.CustomerVisitPortalService;
import com.sairo.be.domain.visit.service.CustomerVisitPortalService.PortalRequests;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A bookmarked read-only index for a customer who already completed link and email verification.
 */
@Profile("!migrate")
@RestController
@RequestMapping("/api/public/customer-visit-links/portal")
@Tag(name = "RESPOND", description = "Email-verified customer visit requests")
public class CustomerVisitPortalIndexController {

  private final CustomerVisitPortalService portal;

  public CustomerVisitPortalIndexController(CustomerVisitPortalService portal) {
    this.portal = portal;
  }

  @Operation(summary = "List verified customer's visit requests without the original link")
  @GetMapping
  @RequiresAccess(AccessPermission.GUEST_LINK)
  public ResponseEntity<PortalRequests> requests(
      @CookieValue(value = CustomerVisitPortalController.COOKIE_NAME, required = false)
          String session) {
    try {
      return ResponseEntity.ok()
          .cacheControl(CacheControl.noStore())
          .body(portal.sessionRequests(session));
    } catch (BusinessException e) {
      if (e.getErrorCode() != ErrorCode.PUBLIC_LINK_NOT_FOUND) throw e;
      return ResponseEntity.ok()
          .cacheControl(CacheControl.noStore())
          .body(new PortalRequests(false, false, List.of()));
    }
  }

  @Operation(summary = "End the verified customer's portal session")
  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RequiresAccess(AccessPermission.GUEST_LINK)
  public ResponseEntity<Void> logout(
      @CookieValue(value = CustomerVisitPortalController.COOKIE_NAME, required = false)
          String session) {
    portal.logout(session);
    return ResponseEntity.noContent()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.SET_COOKIE, CustomerVisitPortalController.cookie("", Duration.ZERO))
        .build();
  }
}
