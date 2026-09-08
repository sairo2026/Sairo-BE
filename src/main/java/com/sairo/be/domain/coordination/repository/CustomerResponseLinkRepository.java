package com.sairo.be.domain.coordination.repository;

import com.sairo.be.domain.coordination.entity.CustomerResponseLink;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerResponseLinkRepository extends JpaRepository<CustomerResponseLink, Long> {

  Optional<CustomerResponseLink> findByTokenHash(String tokenHash);
}
