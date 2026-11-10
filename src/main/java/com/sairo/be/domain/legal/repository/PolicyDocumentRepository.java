package com.sairo.be.domain.legal.repository;

import com.sairo.be.domain.legal.entity.PolicyDocument;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PolicyDocumentRepository extends JpaRepository<PolicyDocument, Long> {

  @Query(
      """
      select p from PolicyDocument p
      where p.status = 'PUBLISHED'
        and p.docType in :docTypes
        and p.effectiveAt <= :now
      order by p.effectiveAt desc
      """)
  List<PolicyDocument> findPublishedInForce(
      @Param("docTypes") Collection<String> docTypes, @Param("now") Instant now);
}
