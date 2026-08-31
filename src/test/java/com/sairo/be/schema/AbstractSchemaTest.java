package com.sairo.be.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
abstract class AbstractSchemaTest {

  @Autowired protected DataSource dataSource;

  protected void withRollback(ConnectionAction action) throws SQLException {
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(false);
      try {
        action.run(conn);
      } finally {
        conn.rollback();
      }
    }
  }

  protected void assertConstraintViolation(String sqlStateOrNull, ConnectionAction action) {
    assertConstraintViolation(sqlStateOrNull, null, action);
  }

  protected void assertConstraintViolation(
      String sqlStateOrNull, String constraintNameOrNull, ConnectionAction action) {
    try {
      withRollback(action);
    } catch (SQLException e) {
      String state = e.getSQLState();
      assertThat(state)
          .as(
              "SQLState는 23(integrity constraint violation) 계열이어야 함, 실제=%s, msg=%s",
              state, e.getMessage())
          .startsWith("23");
      if (sqlStateOrNull != null) {
        assertThat(state).isEqualTo(sqlStateOrNull);
      }
      if (constraintNameOrNull != null) {
        assertThat(e.getMessage())
            .as("위반된 제약 이름이 %s여야 함, 실제 예외 메시지=%s", constraintNameOrNull, e.getMessage())
            .contains("\"" + constraintNameOrNull + "\"");
      }
      return;
    }
    throw new AssertionError("제약 위반이 발생했어야 하는데 아무 예외 없이 성공했다");
  }

  protected long insertUser(Connection conn, String name, String kakaoProviderKey)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO app_user (kakao_provider_key, name) VALUES (?, ?) RETURNING id")) {
      ps.setString(1, kakaoProviderKey);
      ps.setString(2, name);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  protected long insertOffice(Connection conn, String name, String bizNo) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO office (name, representative_name, business_registration_number, real_estate_license_number, phone, address) "
                + "VALUES (?, ?, ?, ?, '010-0000-0000', '서울시') RETURNING id")) {
      ps.setString(1, name);
      ps.setString(2, name);
      ps.setString(3, bizNo);
      ps.setString(4, "LIC-" + UUID.randomUUID().toString().substring(0, 20));
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  protected long insertApprovedSystemAdmin(
      Connection conn, long userId, long officeId, String bizNo) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO office_registration (applicant_user_id, business_registration_number, real_estate_license_number, "
                + "requested_office_name, requested_representative_name, requested_phone, requested_address, "
                + "status, resulting_office_id, reviewed_at) "
                + "SELECT ?, ?, real_estate_license_number, name, representative_name, phone, address, "
                + "'APPROVED', id, now() FROM office WHERE id = ?")) {
      ps.setLong(1, userId);
      ps.setString(2, bizNo);
      ps.setLong(3, officeId);
      ps.executeUpdate();
    }
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO office_membership (office_id, user_id, role, status, reviewed_at) "
                + "VALUES (?, ?, 'ADMIN', 'APPROVED', now()) RETURNING id")) {
      ps.setLong(1, officeId);
      ps.setLong(2, userId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  protected String uniqueKakaoKey(String tag) {
    return "kakao-" + tag + "-" + UUID.randomUUID();
  }

  protected String uniqueBizNo() {
    long n = Math.abs(UUID.randomUUID().getMostSignificantBits()) % 10_000_000_000L;
    return String.format("%010d", n);
  }

  @FunctionalInterface
  protected interface ConnectionAction {
    void run(Connection conn) throws SQLException;
  }
}
