package com.sairo.be.domain.visit.repository;

import com.sairo.be.domain.visit.entity.VisitCustomerTimeSlot;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VisitCustomerTimeSlotRepository
    extends JpaRepository<VisitCustomerTimeSlot, Long> {

  List<VisitCustomerTimeSlot> findByCustomerLinkIdAndResponseVersionOrderByStartsAtAscIdAsc(
      Long customerLinkId, int responseVersion);
}
