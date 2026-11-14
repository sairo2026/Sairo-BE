package com.sairo.be.domain.ops.service;

import com.sairo.be.global.support.ApproverPhoneCipher;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 암호화 전에 저장된 승인자 연락처를 암호문으로 바꾼다. 판별 접두사가 없는 값만 대상으로 삼고, 읽은 값이 그대로일 때만 바꾸므로 여러 번 돌려도 결과가 같다. 연락처 원문은
 * 로그에 남기지 않는다.
 */
@Profile("!migrate")
@Service
public class ApproverPhoneEncryptService {

  private static final int CHUNK = 500;

  private record PlainRow(long id, String phone) {}

  private final JdbcClient jdbcClient;
  private final ApproverPhoneCipher phoneCipher;

  public ApproverPhoneEncryptService(JdbcClient jdbcClient, ApproverPhoneCipher phoneCipher) {
    this.jdbcClient = jdbcClient;
    this.phoneCipher = phoneCipher;
  }

  public int encryptPlainRows() {
    int encrypted = 0;
    long afterId = 0;
    while (true) {
      List<PlainRow> rows =
          jdbcClient
              .sql(
                  "SELECT id, approver_phone_encrypted FROM property_access_approval WHERE id >"
                      + " :afterId AND approver_phone_encrypted IS NOT NULL AND"
                      + " left(approver_phone_encrypted, :prefixLength) <> :prefix ORDER BY id"
                      + " LIMIT :limit")
              .param("afterId", afterId)
              .param("prefixLength", ApproverPhoneCipher.PREFIX.length())
              .param("prefix", ApproverPhoneCipher.PREFIX)
              .param("limit", CHUNK)
              .query(
                  (rs, i) ->
                      new PlainRow(rs.getLong("id"), rs.getString("approver_phone_encrypted")))
              .list();
      if (rows.isEmpty()) {
        return encrypted;
      }
      for (PlainRow row : rows) {
        encrypted +=
            jdbcClient
                .sql(
                    "UPDATE property_access_approval SET approver_phone_encrypted = :encrypted"
                        + " WHERE id = :id AND approver_phone_encrypted = :plain")
                .param("encrypted", phoneCipher.encrypt(row.phone()))
                .param("id", row.id())
                .param("plain", row.phone())
                .update();
      }
      afterId = rows.get(rows.size() - 1).id();
    }
  }
}
