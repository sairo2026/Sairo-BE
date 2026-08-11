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

    @Autowired
    protected DataSource dataSource;

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
        try {
            withRollback(action);
        } catch (SQLException e) {
            String state = e.getSQLState();
            assertThat(state).as("SQLState는 23(integrity constraint violation) 계열이어야 함, 실제=%s, msg=%s", state, e.getMessage())
                    .startsWith("23");
            if (sqlStateOrNull != null) {
                assertThat(state).isEqualTo(sqlStateOrNull);
            }
            return;
        }
        throw new AssertionError("제약 위반이 발생했어야 하는데 아무 예외 없이 성공했다");
    }

    protected long insertMember(Connection conn, String displayName, String email) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO member (display_name, email) VALUES (?, ?) RETURNING id")) {
            ps.setString(1, displayName);
            ps.setString(2, email);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    protected long insertOffice(Connection conn, String name, String bizNo) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO office (name, representative_name, business_registration_number, phone, address_base) "
                        + "VALUES (?, ?, ?, '010-0000-0000', '서울시') RETURNING id")) {
            ps.setString(1, name);
            ps.setString(2, name);
            ps.setString(3, bizNo);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    protected long insertApprovedSystemAdmin(Connection conn, long memberId, long officeId, String bizNo) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO office_registration (applicant_member_id, business_registration_number, status, resulting_office_id, reviewed_at) "
                        + "VALUES (?, ?, 'APPROVED', ?, now())")) {
            ps.setLong(1, memberId);
            ps.setString(2, bizNo);
            ps.setLong(3, officeId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO office_membership (member_id, office_id, role, status, reviewed_at, reviewed_by_type) "
                        + "VALUES (?, ?, 'ADMIN', 'APPROVED', now(), 'SYSTEM') RETURNING id")) {
            ps.setLong(1, memberId);
            ps.setLong(2, officeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    protected String uniqueEmail(String tag) {
        return "test-" + tag + "-" + UUID.randomUUID() + "@example.com";
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
