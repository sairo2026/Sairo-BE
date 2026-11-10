package com.sairo.be.domain.auth.repository;

import com.sairo.be.domain.auth.entity.TermsAgreement;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TermsAgreementRepository extends JpaRepository<TermsAgreement, Long> {

  @Query(
      """
      select a from TermsAgreement a
      where a.userId = :userId
        and a.id in (
          select max(b.id) from TermsAgreement b where b.userId = :userId group by b.termsCode)
      order by a.termsCode
      """)
  List<TermsAgreement> findLatestPerCode(@Param("userId") Long userId);

  Optional<TermsAgreement> findFirstByUserIdAndTermsCodeOrderByIdDesc(
      Long userId, String termsCode);

  Page<TermsAgreement> findByUserId(Long userId, Pageable pageable);

  Page<TermsAgreement> findByUserIdAndTermsCode(Long userId, String termsCode, Pageable pageable);
}
