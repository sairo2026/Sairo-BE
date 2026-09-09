package com.sairo.be.domain.auth.repository;

import com.sairo.be.domain.auth.entity.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

  @Query(
      value =
          "SELECT * FROM app_user WHERE kakao_provider_key = :providerKey AND account_status = 'ACTIVE'",
      nativeQuery = true)
  Optional<AppUser> findActiveByKakaoProviderKey(@Param("providerKey") String kakaoProviderKey);

  Optional<AppUser> findByKakaoProviderKey(String kakaoProviderKey);
}
