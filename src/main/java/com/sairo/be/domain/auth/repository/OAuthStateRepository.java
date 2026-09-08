package com.sairo.be.domain.auth.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
@RequiredArgsConstructor
public class OAuthStateRepository {

  private final JdbcTemplate jdbcTemplate;

  public boolean deleteIfPresent(
      String sessionId, String attributeName, byte[] expectedValue, long now) {
    return jdbcTemplate.update(
            """
            DELETE FROM SPRING_SESSION_ATTRIBUTES a
            USING SPRING_SESSION s
            WHERE a.SESSION_PRIMARY_ID = s.PRIMARY_ID
              AND s.SESSION_ID = ? AND s.EXPIRY_TIME > ?
              AND a.ATTRIBUTE_NAME = ? AND a.ATTRIBUTE_BYTES = ?
            """,
            sessionId,
            now,
            attributeName,
            expectedValue)
        == 1;
  }
}
