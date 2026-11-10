package com.sairo.be.support;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.PlainText;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@TestComponent
@RestController
public class SecurityProbeController {

  public record NameRequest(@PlainText(min = 1, max = 10) String name) {}

  @GetMapping("/api/probe/public")
  @RequiresAccess(AccessPermission.PUBLIC)
  String publicProbe() {
    return "public";
  }

  @GetMapping("/api/probe/authenticated")
  @RequiresAccess(AccessPermission.AUTHENTICATED)
  Long authenticated(@AuthenticationPrincipal StaffPrincipal principal) {
    return principal.userId();
  }

  @GetMapping("/api/probe/staff")
  @RequiresAccess(AccessPermission.STAFF)
  Long staff(@AuthenticationPrincipal StaffPrincipal principal) {
    return principal.officeId();
  }

  @GetMapping("/api/probe/agent-or-owner")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  Long agentOrOwner(@AuthenticationPrincipal StaffPrincipal principal) {
    return principal.officeId();
  }

  @GetMapping("/api/probe/owner")
  @RequiresAccess(AccessPermission.OWNER)
  Long owner(@AuthenticationPrincipal StaffPrincipal principal) {
    return principal.officeId();
  }

  @GetMapping("/api/probe/operator")
  @RequiresAccess(AccessPermission.OPERATOR)
  String operator() {
    return "operator";
  }

  @GetMapping("/api/probe/guest-link")
  @RequiresAccess(AccessPermission.GUEST_LINK)
  String guestLink() {
    return "guest";
  }

  @GetMapping("/api/probe/webhook")
  @RequiresAccess(AccessPermission.EXTERNAL_WEBHOOK)
  String webhook() {
    return "webhook";
  }

  @PostMapping("/api/probe/change")
  @RequiresAccess(AccessPermission.AUTHENTICATED)
  void change() {}

  @GetMapping("/api/probe/unannotated")
  String unannotated() {
    return "unannotated";
  }

  @GetMapping("/api/probe/rate-limited")
  @RequiresAccess(AccessPermission.PUBLIC)
  void rateLimited() {
    throw BusinessException.rateLimited(30);
  }

  @PostMapping("/api/public/probe/validated")
  @RequiresAccess(AccessPermission.PUBLIC)
  void validated(@Valid @RequestBody NameRequest request) {}

  @GetMapping("/api/probe/page")
  @RequiresAccess(AccessPermission.PUBLIC)
  PageResponse<String> page(
      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
    PageQuery query = PageQuery.of(page, size);
    return PageResponse.of(List.of(), query.page(), query.size(), 0);
  }
}
