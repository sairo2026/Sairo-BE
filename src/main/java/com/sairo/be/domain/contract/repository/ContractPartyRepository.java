package com.sairo.be.domain.contract.repository;

import com.sairo.be.domain.contract.entity.PartyRow;
import com.sairo.be.domain.contract.entity.PartySide;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 계약 공동당사자(contract_party)다. 현재 당사자는 removed_at이 비어 있는 행이다. */
@Profile("!migrate")
@Repository
public class ContractPartyRepository {

  private final JdbcClient jdbcClient;

  public ContractPartyRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public List<PartyRow> current(Long contractId) {
    return jdbcClient
        .sql(
            "SELECT id, side, sequence_no, name, phone, customer_id FROM contract_party WHERE"
                + " contract_id = :id AND removed_at IS NULL ORDER BY side DESC, sequence_no")
        .param("id", contractId)
        .query(
            (rs, i) ->
                new PartyRow(
                    rs.getLong("id"),
                    PartySide.valueOf(rs.getString("side")),
                    rs.getInt("sequence_no"),
                    rs.getString("name"),
                    rs.getString("phone"),
                    rs.getObject("customer_id", Long.class)))
        .list();
  }

  public Long insert(
      Long contractId,
      PartySide side,
      int sequenceNo,
      String name,
      String phone,
      Long customerId,
      Instant now) {
    return jdbcClient
        .sql(
            "INSERT INTO contract_party (contract_id, side, sequence_no, name, phone, customer_id,"
                + " created_at) VALUES (:contract, :side, :seq, :name, :phone, :customer, :now)"
                + " RETURNING id")
        .param("contract", contractId)
        .param("side", side.name())
        .param("seq", sequenceNo)
        .param("name", name)
        .param("phone", phone)
        .param("customer", customerId)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  public void update(Long partyId, int sequenceNo, String name, String phone, Long customerId) {
    jdbcClient
        .sql(
            "UPDATE contract_party SET sequence_no = :seq, name = :name, phone = :phone,"
                + " customer_id = :customer WHERE id = :id")
        .param("seq", sequenceNo)
        .param("name", name)
        .param("phone", phone)
        .param("customer", customerId)
        .param("id", partyId)
        .update();
  }

  /**
   * 순서를 다시 매기기 전에 현재 행의 번호를 현재 최댓값과 새 목록 길이를 더한 값보다 큰 임시 번호로 옮긴다. 최종 번호(1부터 새 목록 길이까지)와 부분 유일 인덱스가
   * 부딪치지 않게 하려는 것이다.
   */
  public void shiftCurrent(Long contractId, int room) {
    jdbcClient
        .sql(
            "UPDATE contract_party SET sequence_no = sequence_no + :room + (SELECT"
                + " COALESCE(max(p.sequence_no), 0) FROM contract_party p WHERE p.contract_id ="
                + " :id) WHERE contract_id = :id AND removed_at IS NULL")
        .param("room", room)
        .param("id", contractId)
        .update();
  }

  public void remove(Long partyId, Instant now) {
    jdbcClient
        .sql("UPDATE contract_party SET removed_at = :now WHERE id = :id")
        .param("now", Timestamp.from(now))
        .param("id", partyId)
        .update();
  }

  /** 초안의 한쪽 당사자를 통째로 바꿀 때만 쓴다. 초안 당사자는 다른 표가 참조하지 않는다. */
  public void deleteSide(Long contractId, PartySide side) {
    jdbcClient
        .sql("DELETE FROM contract_party WHERE contract_id = :id AND side = :side")
        .param("id", contractId)
        .param("side", side.name())
        .update();
  }
}
