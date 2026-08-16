package com.sairo.be.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;

class ConstraintViolationTest extends AbstractSchemaTest {

    @Test
    void 다른_사무소의_매물을_조율에_연결하면_실패한다() {
        assertConstraintViolation(null, conn -> {
            long memberA = insertMember(conn, "A", uniqueEmail("a"));
            long officeA = insertOffice(conn, "사무소A", uniqueBizNo());
            long membershipA = insertApprovedSystemAdmin(conn, memberA, officeA, uniqueBizNo());

            long officeB = insertOffice(conn, "사무소B", uniqueBizNo());
            long propertyB = insertProperty(conn, officeB);

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO coordination (office_id, property_id, created_by_member_id, customer_name, customer_phone, link_token_hash, link_expires_at) "
                            + "VALUES (?, ?, ?, '고객', '010-1111-2222', ?, now() + interval '1 day')")) {
                ps.setLong(1, officeA);
                ps.setLong(2, propertyB);
                ps.setLong(3, memberA);
                ps.setString(4, uniqueToken());
                ps.executeUpdate();
            }
        });
    }

    @Test
    void 다른_조율의_후보시간을_확정하면_실패한다() {
        assertConstraintViolation(null, conn -> {
            long memberA = insertMember(conn, "A", uniqueEmail("a"));
            long officeA = insertOffice(conn, "사무소A", uniqueBizNo());
            insertApprovedSystemAdmin(conn, memberA, officeA, uniqueBizNo());
            long propertyA = insertProperty(conn, officeA);

            long coordination1 = insertCoordination(conn, officeA, propertyA, memberA);
            long coordination2 = insertCoordination(conn, officeA, propertyA, memberA);
            long candidateOfCoordination2 = insertCandidateTime(conn, coordination2);

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE coordination SET confirmed_candidate_time_id = ?, confirmed_at = now(), status = 'CONFIRMED' WHERE id = ?")) {
                ps.setLong(1, candidateOfCoordination2);
                ps.setLong(2, coordination1);
                ps.executeUpdate();
            }
        });
    }

    @Test
    void 신청자_본인이_자기_신청을_승인한_것처럼_기록하면_실패한다() {
        assertConstraintViolation(null, conn -> {
            long member = insertMember(conn, "신청자", uniqueEmail("applicant"));
            long office = insertOffice(conn, "사무소", uniqueBizNo());
            long membership = insertApprovedSystemAdmin(conn, member, office, uniqueBizNo());

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO office_membership_status_history (office_membership_id, office_id, from_status, to_status, changed_by_type, changed_by_membership_id) "
                            + "VALUES (?, ?, 'PENDING', 'APPROVED', 'ADMIN', ?)")) {
                ps.setLong(1, membership);
                ps.setLong(2, office);
                ps.setLong(3, membership);
                ps.executeUpdate();
            }
        });
    }

    @Test
    void 관리자_본인의_자진_탈퇴는_자기참조여도_성공한다() throws Exception {
        withRollback(conn -> {
            long member = insertMember(conn, "관리자", uniqueEmail("admin"));
            long office = insertOffice(conn, "사무소", uniqueBizNo());
            long membership = insertApprovedSystemAdmin(conn, member, office, uniqueBizNo());

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO office_membership_status_history (office_membership_id, office_id, from_status, to_status, changed_by_type, changed_by_membership_id) "
                            + "VALUES (?, ?, 'APPROVED', 'REVOKED', 'ADMIN', ?)")) {
                ps.setLong(1, membership);
                ps.setLong(2, office);
                ps.setLong(3, membership);
                int rows = ps.executeUpdate();
                assertThat(rows).isEqualTo(1);
            }
        });
    }

    @Test
    void 최초_ADMIN을_SYSTEM_주체로_생성할_수_있다() throws Exception {
        withRollback(conn -> {
            long member = insertMember(conn, "최초관리자", uniqueEmail("first-admin"));
            long office = insertOffice(conn, "신규사무소", uniqueBizNo());
            long membershipId = insertApprovedSystemAdmin(conn, member, office, uniqueBizNo());
            assertThat(membershipId).isPositive();
        });
    }

    @Test
    void 조율_상태와_시각_컬럼이_안맞으면_실패한다() {
        assertConstraintViolation(null, conn -> {
            long member = insertMember(conn, "A", uniqueEmail("a"));
            long office = insertOffice(conn, "사무소", uniqueBizNo());
            insertApprovedSystemAdmin(conn, member, office, uniqueBizNo());
            long property = insertProperty(conn, office);

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO coordination (office_id, property_id, created_by_member_id, customer_name, customer_phone, status, link_token_hash, link_expires_at) "
                            + "VALUES (?, ?, ?, '고객', '010-1111-2222', 'CONFIRMED', ?, now() + interval '1 day')")) {
                ps.setLong(1, office);
                ps.setLong(2, property);
                ps.setLong(3, member);
                ps.setString(4, uniqueToken());
                ps.executeUpdate();
            }
        });
    }

    @Test
    void 이메일_활성_인증코드는_같은_대상_같은_목적으로_중복될_수_없다() {
        assertConstraintViolation(null, conn -> {
            String email = uniqueEmail("dup-code");
            insertActiveVerificationCode(conn, email, "SIGNUP");
            insertActiveVerificationCode(conn, email, "SIGNUP");
        });
    }

    @Test
    void D90_target_date가_만기일에서_90일_전이_아니면_실패한다() {
        assertConstraintViolation(null, conn -> {
            long office = insertOffice(conn, "사무소", uniqueBizNo());
            long member = insertMember(conn, "A", uniqueEmail("a"));
            insertApprovedSystemAdmin(conn, member, office, uniqueBizNo());
            long property = insertProperty(conn, office);
            long contractId = insertActiveContract(conn, office, property, member, "MONTHLY", "2027-01-01");

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO expiry_task (office_id, property_id, property_contract_id, deal_type, contract_end_date, target_date) "
                            + "VALUES (?, ?, ?, 'MONTHLY', DATE '2027-01-01', DATE '2026-01-01')")) {
                ps.setLong(1, office);
                ps.setLong(2, property);
                ps.setLong(3, contractId);
                ps.executeUpdate();
            }
        });
    }

    @Test
    void 같은_계약에_D90_업무가_중복되면_실패한다() {
        assertConstraintViolation(null, conn -> {
            long office = insertOffice(conn, "사무소", uniqueBizNo());
            long member = insertMember(conn, "A", uniqueEmail("a"));
            insertApprovedSystemAdmin(conn, member, office, uniqueBizNo());
            long property = insertProperty(conn, office);
            long contractId = insertActiveContract(conn, office, property, member, "MONTHLY", "2027-01-01");

            insertOpenExpiryTask(conn, office, property, contractId, "MONTHLY", "2027-01-01");
            insertOpenExpiryTask(conn, office, property, contractId, "MONTHLY", "2027-01-01");
        });
    }

    @Test
    void 다른_회원의_로그인_수단으로_로그인기록을_남기면_실패한다() {
        assertConstraintViolation(null, conn -> {
            long memberA = insertMember(conn, "A", uniqueEmail("a"));
            long memberB = insertMember(conn, "B", uniqueEmail("b"));
            long identityB = insertLoginIdentity(conn, memberB);

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO login_history (member_id, login_identity_id) VALUES (?, ?)")) {
                ps.setLong(1, memberA);
                ps.setLong(2, identityB);
                ps.executeUpdate();
            }
        });
    }

    @Test
    void 본인의_로그인_수단으로_로그인기록을_남길_수_있다() throws Exception {
        withRollback(conn -> {
            long member = insertMember(conn, "A", uniqueEmail("a"));
            long identity = insertLoginIdentity(conn, member);

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO login_history (member_id, login_identity_id) VALUES (?, ?)")) {
                ps.setLong(1, member);
                ps.setLong(2, identity);
                int rows = ps.executeUpdate();
                assertThat(rows).isEqualTo(1);
            }
        });
    }

    @Test
    void 다른_사무소_조율을_재조율_원본으로_참조하면_실패한다() {
        assertConstraintViolation(null, conn -> {
            long memberA = insertMember(conn, "A", uniqueEmail("a"));
            long officeA = insertOffice(conn, "사무소A", uniqueBizNo());
            insertApprovedSystemAdmin(conn, memberA, officeA, uniqueBizNo());
            long propertyA = insertProperty(conn, officeA);
            long coordinationA = insertCoordination(conn, officeA, propertyA, memberA);

            long memberB = insertMember(conn, "B", uniqueEmail("b"));
            long officeB = insertOffice(conn, "사무소B", uniqueBizNo());
            insertApprovedSystemAdmin(conn, memberB, officeB, uniqueBizNo());
            long propertyB = insertProperty(conn, officeB);

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO coordination (office_id, property_id, created_by_member_id, customer_name, customer_phone, link_token_hash, link_expires_at, origin_coordination_id) "
                            + "VALUES (?, ?, ?, '고객', '010-1111-2222', ?, now() + interval '1 day', ?)")) {
                ps.setLong(1, officeB);
                ps.setLong(2, propertyB);
                ps.setLong(3, memberB);
                ps.setString(4, uniqueToken());
                ps.setLong(5, coordinationA);
                ps.executeUpdate();
            }
        });
    }

    @Test
    void 자기_자신을_재조율_원본으로_참조하면_실패한다() {
        assertConstraintViolation(null, conn -> {
            long member = insertMember(conn, "A", uniqueEmail("a"));
            long office = insertOffice(conn, "사무소", uniqueBizNo());
            insertApprovedSystemAdmin(conn, member, office, uniqueBizNo());
            long property = insertProperty(conn, office);
            long coordination = insertCoordination(conn, office, property, member);

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE coordination SET origin_coordination_id = ? WHERE id = ?")) {
                ps.setLong(1, coordination);
                ps.setLong(2, coordination);
                ps.executeUpdate();
            }
        });
    }

    @Test
    void 같은_사무소_조율을_재조율_원본으로_참조할_수_있다() throws Exception {
        withRollback(conn -> {
            long member = insertMember(conn, "A", uniqueEmail("a"));
            long office = insertOffice(conn, "사무소", uniqueBizNo());
            insertApprovedSystemAdmin(conn, member, office, uniqueBizNo());
            long property = insertProperty(conn, office);
            long origin = insertCoordination(conn, office, property, member);

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO coordination (office_id, property_id, created_by_member_id, customer_name, customer_phone, link_token_hash, link_expires_at, origin_coordination_id) "
                            + "VALUES (?, ?, ?, '고객', '010-1111-2222', ?, now() + interval '1 day', ?)")) {
                ps.setLong(1, office);
                ps.setLong(2, property);
                ps.setLong(3, member);
                ps.setString(4, uniqueToken());
                ps.setLong(5, origin);
                int rows = ps.executeUpdate();
                assertThat(rows).isEqualTo(1);
            }
        });
    }

    @Test
    void 매물_소프트삭제_컬럼_조합이_어긋나면_실패한다() {
        assertConstraintViolation(null, conn -> {
            long office = insertOffice(conn, "사무소", uniqueBizNo());
            long propertyId = insertProperty(conn, office);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE property SET deleted_at = now() WHERE id = ?")) {
                ps.setLong(1, propertyId);
                ps.executeUpdate();
            }
        });
    }

    private long insertLoginIdentity(Connection conn, long memberId) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO login_identity (member_id, provider, provider_key) VALUES (?, 'EMAIL', ?) RETURNING id")) {
            ps.setLong(1, memberId);
            ps.setString(2, uniqueEmail("identity"));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertProperty(Connection conn, long officeId) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO property (office_id, address_base, deal_type, property_status, updated_at) "
                        + "VALUES (?, '서울시 어딘가', 'MONTHLY', 'NO_CONTRACT', now()) RETURNING id")) {
            ps.setLong(1, officeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertActiveContract(Connection conn, long officeId, long propertyId, long memberId, String dealType, String contractEndDate) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO property_contract (office_id, property_id, deal_type, created_by_member_id, contract_start_date, contract_end_date, "
                        + "owner_party_name, owner_party_phone, counterparty_name, counterparty_phone, deposit_amount, updated_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_DATE, ?::date, '임대인', '010-0000-0000', '임차인', '010-1111-1111', 100000000, now()) RETURNING id")) {
            ps.setLong(1, officeId);
            ps.setLong(2, propertyId);
            ps.setString(3, dealType);
            ps.setLong(4, memberId);
            ps.setString(5, contractEndDate);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertCoordination(Connection conn, long officeId, long propertyId, long createdByMemberId) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO coordination (office_id, property_id, created_by_member_id, customer_name, customer_phone, link_token_hash, link_expires_at) "
                        + "VALUES (?, ?, ?, '고객', '010-1111-2222', ?, now() + interval '1 day') RETURNING id")) {
            ps.setLong(1, officeId);
            ps.setLong(2, propertyId);
            ps.setLong(3, createdByMemberId);
            ps.setString(4, uniqueToken());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertCandidateTime(Connection conn, long coordinationId) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO coordination_candidate_time (coordination_id, candidate_date, start_time) "
                        + "VALUES (?, CURRENT_DATE + 1, '10:00') RETURNING id")) {
            ps.setLong(1, coordinationId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void insertActiveVerificationCode(Connection conn, String email, String purpose) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO email_verification_code (purpose, target_email, challenge_id, code_hash, expires_at) "
                        + "VALUES (?, ?, gen_random_uuid(), 'x', now() + interval '5 minutes')")) {
            ps.setString(1, purpose);
            ps.setString(2, email);
            ps.executeUpdate();
        }
    }

    private void insertOpenExpiryTask(Connection conn, long officeId, long propertyId, long propertyContractId, String dealType, String contractEndDate) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO expiry_task (office_id, property_id, property_contract_id, deal_type, contract_end_date, target_date) "
                        + "VALUES (?, ?, ?, ?, ?::date, ?::date - 90)")) {
            ps.setLong(1, officeId);
            ps.setLong(2, propertyId);
            ps.setLong(3, propertyContractId);
            ps.setString(4, dealType);
            ps.setString(5, contractEndDate);
            ps.setString(6, contractEndDate);
            ps.executeUpdate();
        }
    }

    private String uniqueToken() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }
}
