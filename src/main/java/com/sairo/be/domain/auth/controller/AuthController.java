package com.sairo.be.domain.auth.controller;

import com.sairo.be.domain.auth.dto.response.AccessConditionResponse;
import com.sairo.be.domain.auth.service.AccessContextService;
import com.sairo.be.domain.auth.service.KakaoAuthFlow;
import com.sairo.be.domain.auth.service.KakaoAuthorizationService;
import com.sairo.be.domain.auth.service.KakaoLoginService;
import com.sairo.be.domain.auth.service.KakaoLoginService.KakaoLoginOutcome;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.security.MemberSessionManager;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.web.ClientRequestInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequiresAccess(AccessPermission.PUBLIC)
@RequestMapping("/api/auth/kakao")
@Tag(name = "AUTH", description = "카카오 로그인과 가입")
public class AuthController {

  private static final int OAUTH_START_SESSION_SECONDS = 600;
  private static final String PURPOSE_GRANT = "GRANT";

  private final KakaoAuthorizationService authorizationService;
  private final KakaoLoginService kakaoLoginService;
  private final AccessContextService accessContextService;
  private final MemberSessionManager sessionManager;
  private final String frontendBaseUrl;

  public AuthController(
      KakaoAuthorizationService authorizationService,
      KakaoLoginService kakaoLoginService,
      AccessContextService accessContextService,
      MemberSessionManager sessionManager,
      @Value("${app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl) {
    this.authorizationService = authorizationService;
    this.kakaoLoginService = kakaoLoginService;
    this.accessContextService = accessContextService;
    this.sessionManager = sessionManager;
    this.frontendBaseUrl = frontendBaseUrl;
  }

  @Operation(
      summary = "카카오 인증 시작",
      description = "카카오 인가 페이지로 이동(302)한다. purpose를 생략하면 로그인·가입이고 GRANT는 반환·보관 권한자의 본인 확인이다.")
  @SecurityRequirements
  @GetMapping("/start")
  public void start(
      @RequestParam(required = false) String purpose,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    KakaoAuthFlow flow = resolveFlow(purpose);
    HttpSession session = request.getSession(true);
    session.setMaxInactiveInterval(OAUTH_START_SESSION_SECONDS);
    response.sendRedirect(authorizationService.start(session, flow));
  }

  @Operation(
      summary = "카카오 인증 콜백",
      description = "일반 용도는 세션을 만들고 접근 판정에 따른 화면으로 이동한다. GRANT 용도는 세션 없이 인증 완료 코드만 전달한다.")
  @SecurityRequirements
  @GetMapping("/callback")
  public void callback(
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String error,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    HttpSession session = request.getSession(false);
    String storedFlow =
        session == null
            ? null
            : (String) session.getAttribute(KakaoAuthorizationService.FLOW_ATTRIBUTE);
    KakaoAuthFlow flow =
        KakaoAuthFlow.GRANT.name().equals(storedFlow) ? KakaoAuthFlow.GRANT : KakaoAuthFlow.LOGIN;

    Optional<String> providerKey =
        authorizationService.resolveProviderKey(session, flow, code, state, error);
    if (providerKey.isEmpty()) {
      response.sendRedirect(
          flow == KakaoAuthFlow.GRANT
              ? frontendBaseUrl + "/grant/verify?error=INVALID_REQUEST"
              : frontendBaseUrl + "/login?error=auth_failed");
      return;
    }
    if (flow == KakaoAuthFlow.GRANT) {
      String credential = kakaoLoginService.issueGrantCredential(providerKey.get());
      response.sendRedirect(frontendBaseUrl + "/grant/verify?credential=" + credential);
      return;
    }
    Optional<KakaoLoginOutcome> outcome =
        kakaoLoginService.loginOrRegister(providerKey.get(), ClientRequestInfo.from(request));
    if (outcome.isEmpty()) {
      response.sendRedirect(frontendBaseUrl + "/login?error=auth_failed");
      return;
    }
    sessionManager.begin(request, response, outcome.get().principal());
    response.sendRedirect(frontendBaseUrl + landingPath(outcome.get()));
  }

  private String landingPath(KakaoLoginOutcome outcome) {
    if (!outcome.signupCompleted()) {
      return "/signup/kakao";
    }
    AccessConditionResponse judgement =
        com.sairo.be.domain.auth.mapper.AuthMapper.toAccessCondition(
            accessContextService.judge(outcome.principal().userId()));
    return switch (judgement.accessCondition()) {
      case "ACTIVE" -> "/";
      case "PRE_VERIFICATION" -> "/office/start";
      case "OFFICE_UNDER_REVIEW" -> "/office/review";
      case "VERIFIED_UNPAID", "PAYMENT_OVERDUE" -> "/billing";
      case "AUTH_REVOKED" -> "/office/verification";
      case "REMOVED" -> "/removed";
      default -> "/return";
    };
  }

  private KakaoAuthFlow resolveFlow(String purpose) {
    if (purpose == null) {
      return KakaoAuthFlow.LOGIN;
    }
    if (PURPOSE_GRANT.equals(purpose)) {
      return KakaoAuthFlow.GRANT;
    }
    throw BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST,
        List.of(
            new FieldErrorItem("purpose", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 purpose 값입니다.")));
  }
}
