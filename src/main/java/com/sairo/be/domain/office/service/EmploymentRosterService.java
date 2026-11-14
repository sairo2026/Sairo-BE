package com.sairo.be.domain.office.service;

import com.sairo.be.domain.office.dto.request.EmploymentRosterCreateRequest;
import com.sairo.be.domain.office.dto.response.EmploymentRosterItemResponse;
import com.sairo.be.domain.office.dto.response.EmploymentRosterListResponse;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.TimeRules;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class EmploymentRosterService {

  private static final Set<String> SOURCES = Set.of("REGISTRATION_DOC", "MANUAL_ENTRY");
  private static final Set<String> PATCH_KEYS = Set.of("employmentEndDate", "legalQualification");

  private record Row(
      long id,
      Long membershipId,
      String personName,
      String qualification,
      LocalDate start,
      LocalDate end,
      String source,
      Instant createdAt) {}

  private final JdbcClient jdbcClient;
  private final DeputyQuotaService quotaService;
  private final Clock clock;

  public EmploymentRosterService(
      JdbcClient jdbcClient, DeputyQuotaService quotaService, Clock clock) {
    this.jdbcClient = jdbcClient;
    this.quotaService = quotaService;
    this.clock = clock;
  }

  @Transactional
  public EmploymentRosterItemResponse create(
      StaffPrincipal owner, EmploymentRosterCreateRequest request) {
    if (!SOURCES.contains(request.confirmationSource())) {
      throw MembershipRules.invalid(
          "confirmationSource", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 확인 출처입니다.");
    }
    if (request.legalQualification() == OfficeMembershipRole.OWNER) {
      throw MembershipRules.invalid(
          "legalQualification", FieldErrorCode.NOT_ALLOWED, "AGENT 또는 DEPUTY만 등록할 수 있습니다.");
    }
    if (request.membershipId() != null) {
      Long found =
          jdbcClient
              .sql("SELECT count(*) FROM office_membership WHERE id = :id AND office_id = :office")
              .param("id", request.membershipId())
              .param("office", owner.officeId())
              .query(Long.class)
              .single();
      if (found == 0) {
        throw MembershipRules.invalid(
            "membershipId", FieldErrorCode.INVALID_VALUE, "사무소의 구성원이 아닙니다.");
      }
    }
    long id =
        jdbcClient
            .sql(
                "INSERT INTO employment_roster (office_id, membership_id, person_name,"
                    + " legal_qualification, employment_start_date, confirmation_source)"
                    + " VALUES (:office, :membership, :name, :qualification, :start, :source)"
                    + " RETURNING id")
            .param("office", owner.officeId())
            .param("membership", request.membershipId())
            .param("name", request.personName().strip())
            .param("qualification", request.legalQualification().name())
            .param("start", Date.valueOf(request.employmentStartDate()))
            .param("source", request.confirmationSource())
            .query(Long.class)
            .single();
    bumpOffice(owner.officeId());
    return item(owner.officeId(), find(owner.officeId(), id), null);
  }

  @Transactional(readOnly = true)
  public EmploymentRosterListResponse list(StaffPrincipal owner, PageQuery pageQuery) {
    long total =
        jdbcClient
            .sql("SELECT count(*) FROM employment_roster WHERE office_id = :office")
            .param("office", owner.officeId())
            .query(Long.class)
            .single();
    List<Row> rows =
        jdbcClient
            .sql(
                "SELECT id, membership_id, person_name, legal_qualification,"
                    + " employment_start_date, employment_end_date, confirmation_source,"
                    + " created_at FROM employment_roster WHERE office_id = :office ORDER BY id"
                    + " LIMIT :limit OFFSET :offset")
            .param("office", owner.officeId())
            .param("limit", pageQuery.size())
            .param("offset", (long) pageQuery.page() * pageQuery.size())
            .query(this::map)
            .list();
    var quota = quotaService.current(owner.officeId());
    return new EmploymentRosterListResponse(
        new EmploymentRosterListResponse.Quota(
            quota.qualifiedCount(), quota.deputyCount(), quota.deputyLimit(), quota.exceeded()),
        rows.stream().map(row -> item(owner.officeId(), row, null)).toList(),
        pageQuery.page(),
        pageQuery.size(),
        total);
  }

  @Transactional
  public EmploymentRosterItemResponse update(
      StaffPrincipal owner, Long rosterId, long expectedVersion, Map<String, Object> body) {
    if (body.isEmpty() || !PATCH_KEYS.containsAll(body.keySet())) {
      throw MembershipRules.invalid(
          "body", FieldErrorCode.NOT_ALLOWED, "employmentEndDate와 legalQualification만 보낼 수 있습니다.");
    }
    long version =
        jdbcClient
            .sql("SELECT version FROM office WHERE id = :id FOR UPDATE")
            .param("id", owner.officeId())
            .query(Long.class)
            .single();
    Row before = findOrNull(owner.officeId(), rosterId);
    if (before == null) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    if (version != expectedVersion) {
      throw BusinessException.versionConflict(version);
    }
    LocalDate end = before.end();
    String qualification = before.qualification();
    if (body.containsKey("employmentEndDate")) {
      Object value = body.get("employmentEndDate");
      try {
        end = value == null ? null : LocalDate.parse(String.valueOf(value));
      } catch (RuntimeException e) {
        throw MembershipRules.invalid(
            "employmentEndDate", FieldErrorCode.INVALID_FORMAT, "날짜 형식이 올바르지 않습니다.");
      }
      if (end != null && end.isBefore(before.start())) {
        throw MembershipRules.invalid(
            "employmentEndDate", FieldErrorCode.OUT_OF_RANGE, "종료일이 시작일보다 앞설 수 없습니다.");
      }
    }
    if (body.containsKey("legalQualification")) {
      String value = String.valueOf(body.get("legalQualification"));
      if (!value.equals("AGENT") && !value.equals("DEPUTY")) {
        throw MembershipRules.invalid(
            "legalQualification", FieldErrorCode.NOT_ALLOWED, "AGENT 또는 DEPUTY만 쓸 수 있습니다.");
      }
      qualification = value;
    }
    jdbcClient
        .sql(
            "UPDATE employment_roster SET employment_end_date = :end, legal_qualification ="
                + " :qualification WHERE id = :id")
        .param("end", end == null ? null : Date.valueOf(end))
        .param("qualification", qualification)
        .param("id", rosterId)
        .update();
    bumpOffice(owner.officeId());
    String warning =
        quotaService.current(owner.officeId()).exceeded() ? "DEPUTY_QUOTA_EXCEEDED" : null;
    return item(owner.officeId(), find(owner.officeId(), rosterId), warning);
  }

  private void bumpOffice(long officeId) {
    jdbcClient
        .sql("UPDATE office SET version = version + 1, updated_at = :now WHERE id = :id")
        .param("now", Timestamp.from(clock.instant()))
        .param("id", officeId)
        .update();
  }

  private Row find(long officeId, long id) {
    Row row = findOrNull(officeId, id);
    if (row == null) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    return row;
  }

  private Row findOrNull(long officeId, long id) {
    return jdbcClient
        .sql(
            "SELECT id, membership_id, person_name, legal_qualification,"
                + " employment_start_date, employment_end_date, confirmation_source, created_at"
                + " FROM employment_roster WHERE id = :id AND office_id = :office")
        .param("id", id)
        .param("office", officeId)
        .query(this::map)
        .optional()
        .orElse(null);
  }

  private Row map(java.sql.ResultSet rs, int rowNumber) throws java.sql.SQLException {
    Date end = rs.getDate("employment_end_date");
    return new Row(
        rs.getLong("id"),
        (Long) rs.getObject("membership_id"),
        rs.getString("person_name"),
        rs.getString("legal_qualification"),
        rs.getDate("employment_start_date").toLocalDate(),
        end == null ? null : end.toLocalDate(),
        rs.getString("confirmation_source"),
        rs.getTimestamp("created_at").toInstant());
  }

  private EmploymentRosterItemResponse item(long officeId, Row row, String warning) {
    var office =
        jdbcClient
            .sql("SELECT updated_at, version FROM office WHERE id = :id")
            .param("id", officeId)
            .query(
                (rs, i) ->
                    new Object[] {rs.getTimestamp("updated_at").toInstant(), rs.getLong("version")})
            .single();
    return new EmploymentRosterItemResponse(
        row.id(),
        officeId,
        row.membershipId(),
        row.personName(),
        row.qualification(),
        row.start(),
        row.end(),
        row.source(),
        TimeRules.toDisplayOffset(row.createdAt()),
        TimeRules.toDisplayOffset((Instant) office[0]),
        (Long) office[1],
        warning);
  }
}
