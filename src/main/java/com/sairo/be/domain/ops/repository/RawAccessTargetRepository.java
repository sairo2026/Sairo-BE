package com.sairo.be.domain.ops.repository;

import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 원문 접근 요청의 대상·연결 문의·대상 사무소를 확인하고 대상 원문을 만든다. */
@Profile("!migrate")
@Repository
public class RawAccessTargetRepository {

  private final JdbcClient jdbcClient;

  public RawAccessTargetRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** 대상 종류별 표 이름이다. 값은 고정 목록에서만 고른다. */
  private static String targetTable(String targetType) {
    return switch (targetType) {
      case "OFFICE" -> "office";
      case "MEMBER" -> "app_user";
      case "SUPPORT_INQUIRY" -> "support_inquiry";
      case "IMPORT_BATCH" -> "import_batch";
      case "OFFICE_REGISTRATION" -> "office_registration";
      default -> throw new IllegalArgumentException("알 수 없는 대상 종류: " + targetType);
    };
  }

  public boolean targetExists(String targetType, long targetId) {
    return jdbcClient
            .sql("SELECT count(*) FROM " + targetTable(targetType) + " WHERE id = :id")
            .param("id", targetId)
            .query(Long.class)
            .single()
        > 0;
  }

  public boolean inquiryExists(long inquiryId) {
    return jdbcClient
            .sql("SELECT count(*) FROM support_inquiry WHERE id = :id")
            .param("id", inquiryId)
            .query(Long.class)
            .single()
        > 0;
  }

  public boolean officeExists(long officeId) {
    return jdbcClient
            .sql("SELECT count(*) FROM office WHERE id = :id")
            .param("id", officeId)
            .query(Long.class)
            .single()
        > 0;
  }

  /** 대상 행을 JSON 한 개로 만든다. 비밀번호 해시·카카오 식별값·저장 경로는 빼고 담는다. */
  public Optional<String> targetSnapshot(String targetType, long targetId) {
    return jdbcClient
        .sql(
            "SELECT (to_jsonb(t) - 'password_hash' - 'kakao_provider_key' - 'file_object_key')"
                + "::text FROM "
                + targetTable(targetType)
                + " t WHERE t.id = :id")
        .param("id", targetId)
        .query(String.class)
        .optional();
  }
}
