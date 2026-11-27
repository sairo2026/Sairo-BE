package com.sairo.be.domain.visit.controller;

import com.sairo.be.domain.visit.service.CustomerVisitPortalService;
import com.sairo.be.domain.visit.service.CustomerVisitPortalService.PortalRequests;
import com.sairo.be.domain.visit.service.CustomerVisitPortalService.ReusableTimes;
import com.sairo.be.domain.visit.service.CustomerVisitPortalService.SendResult;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.web.ClientRequestInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The existing customer link starts OTP verification; the resulting cookie never contains a phone
 * or link token.
 */
@Profile("!migrate")
@RestController
@RequestMapping("/api/public/customer-visit-links/{token}/portal")
@Tag(name = "RESPOND", description = "Email-verified customer visit requests")
public class CustomerVisitPortalController {

  private static final String COOKIE_NAME = "SAIRO_CUSTOMER_PORTAL";
  private static final String COOKIE_PATH = "/api/public/customer-visit-links";

  private final CustomerVisitPortalService portal;

  public CustomerVisitPortalController(CustomerVisitPortalService portal) {
    this.portal = portal;
  }

  public record VerifyRequest(String code) {}

  public record VerifyResult(boolean verified, int expiresInSeconds) {}

  @Operation(summary = "Send a customer visit request email verification code")
  @PostMapping("/otp")
  @RequiresAccess(AccessPermission.GUEST_LINK)
  public ResponseEntity<SendResult> send(@PathVariable String token, HttpServletRequest request) {
    SendResult result = portal.sendCode(token, ClientRequestInfo.from(request).ipAddress());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
  }

  @Operation(summary = "Verify a customer visit request email code")
  @PostMapping("/verify")
  @RequiresAccess(AccessPermission.GUEST_LINK)
  public ResponseEntity<VerifyResult> verify(
      @PathVariable String token, @RequestBody VerifyRequest body, HttpServletRequest request) {
    String session =
        portal.verifyCode(
            token, body == null ? null : body.code(), ClientRequestInfo.from(request).ipAddress());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.SET_COOKIE, cookie(session, CustomerVisitPortalService.SESSION_TTL))
        .body(new VerifyResult(true, (int) CustomerVisitPortalService.SESSION_TTL.toSeconds()));
  }

  @Operation(summary = "List the verified customer's visit requests")
  @GetMapping
  @RequiresAccess(AccessPermission.GUEST_LINK)
  public ResponseEntity<PortalRequests> requests(
      @PathVariable String token,
      @CookieValue(value = COOKIE_NAME, required = false) String session,
      HttpServletRequest request) {
    String ip = ClientRequestInfo.from(request).ipAddress();
    boolean available = portal.canVerify(token, ip);
    if (!available || session == null || session.isBlank()) {
      return ResponseEntity.ok()
          .cacheControl(CacheControl.noStore())
          .body(new PortalRequests(available, false, List.of()));
    }
    try {
      return ResponseEntity.ok()
          .cacheControl(CacheControl.noStore())
          .body(portal.requests(token, session, ip));
    } catch (BusinessException e) {
      if (e.getErrorCode() != ErrorCode.PUBLIC_LINK_NOT_FOUND) throw e;
      return ResponseEntity.ok()
          .cacheControl(CacheControl.noStore())
          .body(new PortalRequests(true, false, List.of()));
    }
  }

  @Operation(summary = "Preview reusable visit times for the verified customer")
  @GetMapping("/reusable-times")
  @RequiresAccess(AccessPermission.GUEST_LINK)
  public ResponseEntity<ReusableTimes> reusableTimes(
      @PathVariable String token,
      @CookieValue(value = COOKIE_NAME, required = false) String session,
      HttpServletRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(portal.reusableTimes(token, session, ClientRequestInfo.from(request).ipAddress()));
  }

  @Operation(summary = "End customer visit request verification")
  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RequiresAccess(AccessPermission.GUEST_LINK)
  public ResponseEntity<Void> logout(
      @CookieValue(value = COOKIE_NAME, required = false) String session) {
    portal.logout(session);
    return ResponseEntity.noContent()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO))
        .build();
  }

  private static String cookie(String value, Duration ttl) {
    return ResponseCookie.from(COOKIE_NAME, value)
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .path(COOKIE_PATH)
        .maxAge(ttl)
        .build()
        .toString();
  }
}
