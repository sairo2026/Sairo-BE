package com.sairo.be.domain.auth.repository;

import com.sairo.be.domain.auth.entity.AccountStatus;
import com.sairo.be.domain.auth.entity.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

  Optional<AppUser> findByKakaoProviderKeyAndAccountStatus(
      String kakaoProviderKey, AccountStatus accountStatus);

  Optional<AppUser> findByKakaoProviderKey(String kakaoProviderKey);
}
