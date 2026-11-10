package com.sairo.be.domain.legal.repository;

import com.sairo.be.domain.legal.entity.TermsCatalog;
import com.sairo.be.domain.legal.entity.TermsCatalogId;
import com.sairo.be.domain.legal.entity.TermsDisplayContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TermsCatalogRepository extends JpaRepository<TermsCatalog, TermsCatalogId> {

  @Query(
      """
      select t from TermsCatalog t
      where t.displayContext = :context
        and t.effectiveAt <= :now
        and t.effectiveAt = (
          select max(o.effectiveAt) from TermsCatalog o
          where o.termsCode = t.termsCode and o.effectiveAt <= :now)
      order by t.termsCode
      """)
  List<TermsCatalog> findInForce(
      @Param("context") TermsDisplayContext context, @Param("now") Instant now);

  @Query(
      """
      select t from TermsCatalog t
      where t.termsCode = :code
        and t.effectiveAt <= :now
      order by t.effectiveAt desc
      limit 1
      """)
  Optional<TermsCatalog> findInForceByCode(@Param("code") String code, @Param("now") Instant now);
}
