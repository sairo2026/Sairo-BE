package com.sairo.be.domain.auth.repository;

import com.sairo.be.domain.auth.entity.NotificationChannel;
import com.sairo.be.domain.auth.entity.NotificationPreference;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceRepository
    extends JpaRepository<NotificationPreference, Long> {

  List<NotificationPreference> findByUserId(Long userId);

  Optional<NotificationPreference> findByUserIdAndChannel(Long userId, NotificationChannel channel);
}
