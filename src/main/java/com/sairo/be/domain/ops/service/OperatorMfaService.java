package com.sairo.be.domain.ops.service;

import com.sairo.be.domain.ops.repository.OperatorAccountRepository;
import com.sairo.be.global.support.TextCipher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class OperatorMfaService {

  static final int MAX_FAILURES = 5;
  static final Duration LOCK_DURATION = Duration.ofMinutes(15);

  public enum Result {
    SUCCESS,
    INVALID,
    LOCKED
  }

  public record Outcome(Result result, long retryAfterSeconds) {}

  private final OperatorAccountRepository accountRepository;
  private final TotpService totpService;
  private final TextCipher textCipher;
  private final Clock clock;

  public OperatorMfaService(
      OperatorAccountRepository accountRepository,
      TotpService totpService,
      TextCipher textCipher,
      Clock clock) {
    this.accountRepository = accountRepository;
    this.totpService = totpService;
    this.textCipher = textCipher;
    this.clock = clock;
  }

  @Transactional
  public Outcome verify(long operatorId, String code) {
    var account = accountRepository.lockById(operatorId);
    if (account.isEmpty()) {
      return new Outcome(Result.INVALID, 0);
    }
    Instant now = clock.instant();
    int failures = account.get().mfaFailedCount();
    Instant lockedUntil = account.get().mfaLockedUntil();
    if (lockedUntil != null && lockedUntil.isAfter(now)) {
      return new Outcome(Result.LOCKED, Duration.between(now, lockedUntil).toSeconds() + 1);
    }
    if (lockedUntil != null) {
      failures = 0;
    }
    String secret = textCipher.decrypt(account.get().totpSecretEncrypted());
    if (totpService.verify(secret, code, now)) {
      accountRepository.updateMfaState(operatorId, 0, null);
      return new Outcome(Result.SUCCESS, 0);
    }
    failures += 1;
    if (failures >= MAX_FAILURES) {
      accountRepository.updateMfaState(operatorId, 0, now.plus(LOCK_DURATION));
      return new Outcome(Result.LOCKED, LOCK_DURATION.toSeconds());
    }
    accountRepository.updateMfaState(operatorId, failures, null);
    return new Outcome(Result.INVALID, 0);
  }
}
