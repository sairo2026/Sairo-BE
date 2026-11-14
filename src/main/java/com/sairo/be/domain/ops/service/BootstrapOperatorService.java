package com.sairo.be.domain.ops.service;

import com.sairo.be.domain.ops.repository.OperatorAccountRepository;
import com.sairo.be.domain.ops.repository.OperatorInvitationRepository;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.global.config.BootstrapOperatorProperties;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.TokenGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class BootstrapOperatorService {

  public enum Outcome {
    CREATED,
    OPERATOR_EXISTS,
    INVITATION_PENDING,
    INVALID_CONFIGURATION
  }

  static final String SYSTEM_INVITER_KEY = "system:bootstrap";
  static final String SYSTEM_INVITER_NAME = "시스템";
  static final String DEFAULT_INVITEE_NAME = "최초 운영자";
  private static final String BOOTSTRAP_LOCK = "ops:bootstrap-operator";
  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
  private static final int MAX_EMAIL_LENGTH = 254;
  private static final int MAX_NAME_LENGTH = 50;
  private static final Logger log = LoggerFactory.getLogger(BootstrapOperatorService.class);

  private final OperatorAccountRepository accounts;
  private final OperatorInvitationRepository invitations;
  private final OpsAuditLogRepository auditLog;
  private final AdvisoryLock advisoryLock;
  private final TokenGenerator tokenGenerator;
  private final BootstrapOperatorProperties properties;
  private final Clock clock;
  private final String frontendBaseUrl;

  public BootstrapOperatorService(
      OperatorAccountRepository accounts,
      OperatorInvitationRepository invitations,
      OpsAuditLogRepository auditLog,
      AdvisoryLock advisoryLock,
      TokenGenerator tokenGenerator,
      BootstrapOperatorProperties properties,
      Clock clock,
      @Value("${app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl) {
    this.accounts = accounts;
    this.invitations = invitations;
    this.auditLog = auditLog;
    this.advisoryLock = advisoryLock;
    this.tokenGenerator = tokenGenerator;
    this.properties = properties;
    this.clock = clock;
    this.frontendBaseUrl = frontendBaseUrl;
  }

  @Transactional
  public Outcome bootstrap() {
    advisoryLock.lockForTransaction(BOOTSTRAP_LOCK);
    if (accounts.countActive() > 0) {
      log.info("활성 운영자가 있어 최초 운영자 초대를 만들지 않는다. 설정을 끄는 것을 권장한다.");
      return Outcome.OPERATOR_EXISTS;
    }
    String email = normalizedEmail();
    String name = inviteeName();
    String fileSetting = properties.invitationFile();
    if (email == null || name == null || fileSetting == null || fileSetting.isBlank()) {
      log.warn("최초 운영자 초대 설정이 올바르지 않아 초대를 만들지 않는다.");
      return Outcome.INVALID_CONFIGURATION;
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    long inviterId = accounts.ensureSystemAccount(SYSTEM_INVITER_KEY, SYSTEM_INVITER_NAME);
    if (invitations.existsLivePendingInvitedBy(inviterId, now)) {
      log.info("만료되지 않은 최초 운영자 초대가 이미 있어 새로 만들지 않는다.");
      return Outcome.INVITATION_PENDING;
    }
    advisoryLock.lockForTransaction("ops:invitation:" + email);
    for (Long pendingId : invitations.lockPendingByEmail(email)) {
      invitations.markExpired(pendingId, now);
      auditLog.insert(
          inviterId, "OPERATOR_INVITATION_EXPIRED", "OPERATOR_INVITATION", pendingId, null, now);
    }
    String token = tokenGenerator.randomToken();
    Instant expiresAt = now.plus(OperatorManagementService.INVITATION_VALIDITY);
    long invitationId =
        invitations.insert(
            name, email, OperatorInvitationAuthService.sha256(token), inviterId, now, expiresAt);
    invitations.insertRole(invitationId, OperatorRole.SECURITY_ADMIN);
    auditLog.insert(
        inviterId,
        "OPERATOR_BOOTSTRAP_INVITATION_CREATED",
        "OPERATOR_INVITATION",
        invitationId,
        "roles=" + OperatorRole.SECURITY_ADMIN.name(),
        now);
    String link =
        frontendBaseUrl
            + "/ops/invitations/accept?token="
            + URLEncoder.encode(token, StandardCharsets.UTF_8);
    String lineBreak = System.lineSeparator();
    writeInvitationFile(
        Path.of(fileSetting),
        "최초 운영자 초대 링크: "
            + link
            + lineBreak
            + "만료 시각(UTC): "
            + expiresAt
            + lineBreak
            + "링크는 한 번만 쓸 수 있다. 카카오 인증과 2차 인증 등록을 마치면 이 파일을 지운다."
            + lineBreak);
    log.info("최초 운영자 초대를 만들고 서버의 소유자 전용 파일에 링크를 기록했다.");
    return Outcome.CREATED;
  }

  private String normalizedEmail() {
    String email = properties.email();
    if (email == null) {
      return null;
    }
    String normalized = email.strip().toLowerCase(Locale.ROOT);
    if (normalized.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(normalized).matches()) {
      return null;
    }
    return normalized;
  }

  private String inviteeName() {
    String name = properties.name();
    String resolved = name == null || name.isBlank() ? DEFAULT_INVITEE_NAME : name.strip();
    return resolved.length() > MAX_NAME_LENGTH ? null : resolved;
  }

  private static void writeInvitationFile(Path target, String content) {
    boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    try {
      Path parent = target.toAbsolutePath().getParent();
      Files.createDirectories(parent);
      Path temp =
          posix
              ? Files.createTempFile(parent, ".bootstrap-", ".tmp", ownerOnly())
              : Files.createTempFile(parent, ".bootstrap-", ".tmp");
      try {
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
          Files.move(
              temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(temp);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("최초 운영자 초대 파일을 쓸 수 없다.", e);
    }
  }

  private static FileAttribute<Set<PosixFilePermission>> ownerOnly() {
    return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
  }
}
