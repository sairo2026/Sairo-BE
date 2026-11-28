package com.sairo.be.domain.visit.controller;

import com.sairo.be.domain.visit.dto.response.ConfirmedScheduleResponses.PublicConfirmedVisitResponse;
import com.sairo.be.domain.visit.dto.response.CustomerAvailableTimesResponse;
import com.sairo.be.domain.visit.dto.response.PublicVisitLinkResponse;
import com.sairo.be.domain.visit.service.CustomerVisitPortalService;
import com.sairo.be.domain.visit.service.CustomerVisitPortalService.PortalRequests;
import com.sairo.be.domain.visit.service.CustomerVisitPortalService.ReusableTimes;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

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

  @Operation(summary = "Open an active visit request using the verified customer portal")
  @GetMapping("/requests/{coordinationId}")
  @RequiresAccess(AccessPermission.GUEST_LINK)
  public ResponseEntity<PublicVisitLinkResponse> request(
      @PathVariable Long coordinationId,
      @CookieValue(value = CustomerVisitPortalController.COOKIE_NAME, required = false)
          String session) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(portal.sessionRequest(session, coordinationId));
  }

  @Operation(summary = "Read a verified customer's confirmed visit schedule")
  @GetMapping("/requests/{coordinationId}/confirmed")
  @RequiresAccess(AccessPermission.GUEST_LINK)
  public ResponseEntity<PublicConfirmedVisitResponse> confirmed(
      @PathVariable Long coordinationId,
      @CookieValue(value = CustomerVisitPortalController.COOKIE_NAME, required = false)
          String session) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(portal.sessionConfirmed(session, coordinationId));
  }

  @Operation(summary = "Preview still-valid times from another request for this verified customer")
  @GetMapping("/requests/{coordinationId}/reusable-times")
  @RequiresAccess(AccessPermission.GUEST_LINK)
  public ResponseEntity<ReusableTimes> reusableTimes(
      @PathVariable Long coordinationId,
      @CookieValue(value = CustomerVisitPortalController.COOKIE_NAME, required = false)
          String session) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(portal.sessionReusableTimes(session, coordinationId));
  }

  @Operation(summary = "Submit times for an active request using the verified customer portal")
  @PostMapping("/requests/{coordinationId}/available-times")
  @RequiresAccess(AccessPermission.GUEST_LINK)
  public ResponseEntity<CustomerAvailableTimesResponse> submit(
      @PathVariable Long coordinationId,
      @CookieValue(value = CustomerVisitPortalController.COOKIE_NAME, required = false)
          String session,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(portal.sessionSubmit(session, coordinationId, body, idempotencyKey));
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
