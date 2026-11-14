package com.sairo.be.domain.auth.repository;

import com.sairo.be.domain.auth.entity.PasswordResetToken;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

  Optional<PasswordResetToken> findByTokenHash(String tokenHash);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      update PasswordResetToken t set t.supersededAt = :now
      where t.userId = :userId and t.consumedAt is null and t.supersededAt is null
      """)
  int supersedeActive(@Param("userId") Long userId, @Param("now") Instant now);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      update PasswordResetToken t set t.consumedAt = :now
      where t.id = :id and t.consumedAt is null and t.supersededAt is null
      """)
  int consume(@Param("id") Long id, @Param("now") Instant now);
}
