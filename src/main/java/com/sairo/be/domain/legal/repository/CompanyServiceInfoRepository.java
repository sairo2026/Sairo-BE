package com.sairo.be.domain.legal.repository;

import com.sairo.be.domain.legal.entity.CompanyServiceInfo;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompanyServiceInfoRepository extends JpaRepository<CompanyServiceInfo, Long> {

  @Query(
      """
      select c from CompanyServiceInfo c
      where c.publishedAt is not null and c.publishedAt <= :now
      order by c.versionNo desc
      limit 1
      """)
  Optional<CompanyServiceInfo> findLatestPublished(@Param("now") Instant now);
}
