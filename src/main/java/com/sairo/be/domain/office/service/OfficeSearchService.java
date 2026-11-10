package com.sairo.be.domain.office.service;

import com.sairo.be.domain.office.dto.response.OfficeSearchItemResponse;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class OfficeSearchService {

  private static final String VERIFIED =
      " FROM office o WHERE EXISTS (SELECT 1 FROM office_registration r WHERE"
          + " r.resulting_office_id = o.id AND r.status = 'APPROVED')";

  private final JdbcClient jdbcClient;

  public OfficeSearchService(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Transactional(readOnly = true)
  public PageResponse<OfficeSearchItemResponse> search(
      String businessRegistrationNumber, String name, String region, PageQuery pageQuery) {
    boolean hasNumber = businessRegistrationNumber != null && !businessRegistrationNumber.isBlank();
    boolean hasNameAndRegion =
        name != null && !name.isBlank() && region != null && !region.isBlank();
    if (!hasNumber && !hasNameAndRegion) {
      throw BusinessException.withFieldErrors(
          ErrorCode.INVALID_REQUEST,
          List.of(
              new FieldErrorItem(
                  "businessRegistrationNumber",
                  FieldErrorCode.REQUIRED,
                  "사업자등록번호 또는 사무소명과 지역을 함께 입력해야 합니다.")));
    }
    StringBuilder where = new StringBuilder(VERIFIED);
    List<Object> params = new ArrayList<>();
    if (hasNumber) {
      where.append(" AND o.business_registration_number = ?");
      params.add(businessRegistrationNumber.strip().replace("-", ""));
    } else {
      where.append(" AND o.name ILIKE ? ESCAPE '\\'");
      params.add("%" + escape(name.strip()) + "%");
      where.append(
          " AND (o.registration_authority ILIKE ? ESCAPE '\\' OR o.address ILIKE ? ESCAPE '\\')");
      String like = "%" + escape(region.strip()) + "%";
      params.add(like);
      params.add(like);
    }
    Long total =
        jdbcClient.sql("SELECT count(*)" + where).params(params).query(Long.class).single();
    List<Object> pageParams = new ArrayList<>(params);
    pageParams.add(pageQuery.size());
    pageParams.add((long) pageQuery.page() * pageQuery.size());
    List<OfficeSearchItemResponse> items =
        jdbcClient
            .sql(
                "SELECT o.id, o.name, o.entity_type, o.registration_authority,"
                    + " o.real_estate_license_number, o.address"
                    + where
                    + " ORDER BY o.id LIMIT ? OFFSET ?")
            .params(pageParams)
            .query(
                (rs, i) ->
                    new OfficeSearchItemResponse(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("entity_type"),
                        rs.getString("registration_authority"),
                        rs.getString("real_estate_license_number"),
                        rs.getString("address")))
            .list();
    return PageResponse.of(items, pageQuery.page(), pageQuery.size(), total);
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
