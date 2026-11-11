package com.sairo.be.domain.office.service;

import com.sairo.be.domain.office.dto.response.OfficeMembershipListItemResponse;
import com.sairo.be.domain.office.entity.OfficeMembershipStatus;
import com.sairo.be.domain.office.entity.OfficeRequestSource;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StaffRole;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.StatusFilter;
import com.sairo.be.global.support.TimeRules;
import com.sairo.be.global.support.WithdrawnMemberDisplay;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class OfficeMemberListService {

  private final JdbcClient jdbcClient;

  public OfficeMemberListService(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Transactional(readOnly = true)
  public PageResponse<OfficeMembershipListItemResponse> list(
      StaffPrincipal principal, String statusParam, PageQuery pageQuery) {
    boolean owner = principal.role() == StaffRole.OWNER;
    Set<OfficeMembershipStatus> statuses =
        StatusFilter.parse(
            statusParam, OfficeMembershipStatus.class, EnumSet.of(OfficeMembershipStatus.APPROVED));
    if (!owner
        && (statuses.contains(OfficeMembershipStatus.PENDING)
            || statuses.contains(OfficeMembershipStatus.SUSPENDED))) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    String inList = String.join(",", statuses.stream().map(s -> "'" + s.name() + "'").toList());
    long total =
        jdbcClient
            .sql(
                "SELECT count(*) FROM office_membership WHERE office_id = :office AND status IN ("
                    + inList
                    + ")")
            .param("office", principal.officeId())
            .query(Long.class)
            .single();
    List<OfficeMembershipListItemResponse> items =
        jdbcClient
            .sql(
                "SELECT m.id, m.user_id, m.role, m.status, m.approved_at, m.request_source,"
                    + " m.invitation_accepted_at, m.invitation_expires_at, m.created_at,"
                    + " m.updated_at, m.version, m.qualification_number,"
                    + " m.employment_report_confirmed, m.training_confirmed,"
                    + " m.withdrawn_alias_no, u.name, u.account_status FROM office_membership m"
                    + " JOIN app_user u ON u.id = m.user_id WHERE m.office_id = :office AND"
                    + " m.status IN ("
                    + inList
                    + ") ORDER BY m.id LIMIT :limit OFFSET :offset")
            .param("office", principal.officeId())
            .param("limit", pageQuery.size())
            .param("offset", (long) pageQuery.page() * pageQuery.size())
            .query(
                (rs, i) -> {
                  String name =
                      WithdrawnMemberDisplay.name(
                          "WITHDRAWN".equals(rs.getString("account_status")),
                          rs.getString("name"),
                          (Integer) rs.getObject("withdrawn_alias_no"));
                  String status = rs.getString("status");
                  if (!owner) {
                    return new OfficeMembershipListItemResponse(
                        rs.getLong("id"),
                        null,
                        name,
                        rs.getString("role"),
                        status,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
                  }
                  OfficeRequestSource source =
                      OfficeRequestSource.valueOf(rs.getString("request_source"));
                  Timestamp created = rs.getTimestamp("created_at");
                  Timestamp invitationExpires = rs.getTimestamp("invitation_expires_at");
                  boolean pending = "PENDING".equals(status);
                  var expiresAt =
                      !pending
                          ? null
                          : source == OfficeRequestSource.INVITE
                              ? invitationExpires.toInstant()
                              : created.toInstant().plus(Duration.ofDays(30));
                  Timestamp approved = rs.getTimestamp("approved_at");
                  Timestamp accepted = rs.getTimestamp("invitation_accepted_at");
                  return new OfficeMembershipListItemResponse(
                      rs.getLong("id"),
                      rs.getLong("user_id"),
                      name,
                      rs.getString("role"),
                      status,
                      approved == null ? null : TimeRules.toDisplayOffset(approved.toInstant()),
                      source.name(),
                      TimeRules.toDisplayOffset(expiresAt),
                      accepted == null ? null : TimeRules.toDisplayOffset(accepted.toInstant()),
                      rs.getString("qualification_number"),
                      (Boolean) rs.getObject("employment_report_confirmed"),
                      (Boolean) rs.getObject("training_confirmed"),
                      "APPROVED".equals(status),
                      principal.officeId(),
                      TimeRules.toDisplayOffset(created.toInstant()),
                      TimeRules.toDisplayOffset(rs.getTimestamp("updated_at").toInstant()),
                      rs.getLong("version"));
                })
            .list();
    return PageResponse.of(items, pageQuery.page(), pageQuery.size(), total);
  }
}
