package com.sairo.be.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "terms_agreement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TermsAgreement {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "terms_code", nullable = false, length = 30)
  private String termsCode;

  @Column(name = "terms_version", nullable = false, length = 20)
  private String termsVersion;

  @Column(nullable = false)
  private boolean agreed;

  @Column(name = "recorded_at", nullable = false)
  private Instant recordedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "consent_path", nullable = false, length = 20)
  private ConsentPath consentPath;

  @Column(name = "office_id", updatable = false)
  private Long officeId;

  public static TermsAgreement record(
      Long userId,
      String termsCode,
      String termsVersion,
      boolean agreed,
      ConsentPath consentPath,
      Instant recordedAt) {
    TermsAgreement agreement = new TermsAgreement();
    agreement.userId = userId;
    agreement.termsCode = termsCode;
    agreement.termsVersion = termsVersion;
    agreement.agreed = agreed;
    agreement.consentPath = consentPath;
    agreement.recordedAt = recordedAt;
    return agreement;
  }

  public static TermsAgreement recordForOffice(
      Long userId,
      Long officeId,
      String termsCode,
      String termsVersion,
      boolean agreed,
      ConsentPath consentPath,
      Instant recordedAt) {
    TermsAgreement agreement =
        record(userId, termsCode, termsVersion, agreed, consentPath, recordedAt);
    agreement.officeId = officeId;
    return agreement;
  }
}
