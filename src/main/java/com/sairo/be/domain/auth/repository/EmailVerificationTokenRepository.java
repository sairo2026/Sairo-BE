package com.sairo.be.domain.auth.repository;

import com.sairo.be.domain.auth.entity.EmailVerificationToken;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationTokenRepository
    extends JpaRepository<EmailVerificationToken, Long> {

  Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      update EmailVerificationToken t set t.supersededAt = :now
      where t.email = :email and t.consumedAt is null and t.supersededAt is null
      """)
  int supersedeActive(@Param("email") String email, @Param("now") Instant now);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      update EmailVerificationToken t set t.consumedAt = :now
      where t.id = :id and t.consumedAt is null and t.supersededAt is null
      """)
  int consume(@Param("id") Long id, @Param("now") Instant now);
}
