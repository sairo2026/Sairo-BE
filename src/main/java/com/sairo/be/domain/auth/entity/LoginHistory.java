package com.sairo.be.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.net.InetAddress;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "login_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoginHistory {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "auth_method", nullable = false, length = 10)
  private AuthMethod authMethod;

  @JdbcTypeCode(SqlTypes.INET)
  @Column(name = "ip_address", columnDefinition = "inet")
  private InetAddress ipAddress;

  @Column(name = "user_agent", length = 500)
  private String userAgent;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public static LoginHistory record(
      Long userId, AuthMethod authMethod, InetAddress ipAddress, String userAgent, Instant at) {
    LoginHistory history = new LoginHistory();
    history.userId = userId;
    history.authMethod = authMethod;
    history.ipAddress = ipAddress;
    history.userAgent = userAgent;
    history.createdAt = at;
    return history;
  }
}
