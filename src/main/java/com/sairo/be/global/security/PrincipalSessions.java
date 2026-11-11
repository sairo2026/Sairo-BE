package com.sairo.be.global.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/** 회원·운영자의 저장된 세션을 목록으로 보거나 끝낸다. 목록의 세션 식별자는 실제 세션 ID가 아닌 불투명 값이다. */
@Profile("!migrate")
@Component
@SuppressWarnings({"rawtypes", "unchecked"})
public class PrincipalSessions {

  public record SessionInfo(String opaqueId, Instant createdAt, Instant lastSeenAt) {}

  private final FindByIndexNameSessionRepository sessionRepository;

  public PrincipalSessions(FindByIndexNameSessionRepository<?> sessionRepository) {
    this.sessionRepository = sessionRepository;
  }

  public static String memberPrincipalName(long userId) {
    return String.valueOf(userId);
  }

  public static String operatorPrincipalName(long operatorId) {
    return OperatorAuthentication.NAME_PREFIX + operatorId;
  }

  public List<SessionInfo> list(String principalName) {
    Map<String, Session> sessions = sessionRepository.findByPrincipalName(principalName);
    List<SessionInfo> result = new ArrayList<>();
    sessions.forEach(
        (id, session) ->
            result.add(
                new SessionInfo(
                    opaqueId(id), session.getCreationTime(), session.getLastAccessedTime())));
    result.sort(Comparator.comparing(SessionInfo::createdAt).reversed());
    return result;
  }

  /** 불투명 식별자에 해당하는 세션을 끝낸다. 없으면 빈 값을 돌려준다. */
  public Optional<String> terminateByOpaqueId(String principalName, String opaqueId) {
    Map<String, Session> sessions = sessionRepository.findByPrincipalName(principalName);
    for (String id : sessions.keySet()) {
      if (opaqueId(id).equals(opaqueId)) {
        sessionRepository.deleteById(id);
        return Optional.of(opaqueId);
      }
    }
    return Optional.empty();
  }

  public int terminateAll(String principalName) {
    Map<String, Session> sessions = sessionRepository.findByPrincipalName(principalName);
    sessions.keySet().forEach(sessionRepository::deleteById);
    return sessions.size();
  }

  /** 운영자의 저장된 세션에 담긴 역할을 새 값으로 바꾼다. */
  public int replaceOperatorPrincipal(OperatorPrincipal principal) {
    Map<String, Session> sessions =
        sessionRepository.findByPrincipalName(operatorPrincipalName(principal.operatorId()));
    int refreshed = 0;
    for (String sessionId : sessions.keySet()) {
      Session stored = (Session) sessionRepository.findById(sessionId);
      if (stored == null) {
        continue;
      }
      SecurityContext context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(new OperatorAuthentication(principal));
      stored.setAttribute(
          HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
      sessionRepository.save(stored);
      refreshed++;
    }
    return refreshed;
  }

  public static String opaqueId(String sessionId) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(StandardCharsets.UTF_8));
      return "s-" + HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
