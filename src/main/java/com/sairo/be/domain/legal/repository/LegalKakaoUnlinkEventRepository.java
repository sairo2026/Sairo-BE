package com.sairo.be.domain.legal.repository;

import com.sairo.be.domain.legal.entity.KakaoUnlinkDirection;
import com.sairo.be.domain.legal.entity.LegalKakaoUnlinkEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LegalKakaoUnlinkEventRepository
    extends JpaRepository<LegalKakaoUnlinkEvent, Long> {

  @Query(
      """
      select count(e) > 0 from LegalKakaoUnlinkEvent e
      where e.direction = :direction and e.kakaoKeyHmac = :hmac
        and e.referrerType = :referrerType and e.occurredAt >= :since
        and e.status <> com.sairo.be.domain.legal.entity.KakaoUnlinkStatus.DUPLICATE_IGNORED
      """)
  boolean existsRecent(
      @Param("direction") KakaoUnlinkDirection direction,
      @Param("hmac") String hmac,
      @Param("referrerType") String referrerType,
      @Param("since") Instant since);

  @Query(
      """
      select e.id from LegalKakaoUnlinkEvent e
      where e.direction = com.sairo.be.domain.legal.entity.KakaoUnlinkDirection.OUTBOUND_CALL
        and e.status in (com.sairo.be.domain.legal.entity.KakaoUnlinkStatus.PENDING,
                         com.sairo.be.domain.legal.entity.KakaoUnlinkStatus.FAILED_RETRYING)
        and e.nextAttemptAt <= :now
      order by e.nextAttemptAt, e.id
      """)
  List<Long> findDueIds(@Param("now") Instant now, Pageable pageable);

  @Query(
      """
      select e.id from LegalKakaoUnlinkEvent e
      where e.direction = com.sairo.be.domain.legal.entity.KakaoUnlinkDirection.OUTBOUND_CALL
        and e.status in (com.sairo.be.domain.legal.entity.KakaoUnlinkStatus.PENDING,
                         com.sairo.be.domain.legal.entity.KakaoUnlinkStatus.FAILED_RETRYING)
        and e.occurredAt <= :cutoff
      """)
  List<Long> findExpiredIds(@Param("cutoff") Instant cutoff);
}
