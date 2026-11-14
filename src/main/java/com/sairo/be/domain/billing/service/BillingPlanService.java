package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.dto.response.BillingPlanListResponse;
import com.sairo.be.domain.billing.mapper.BillingMapper;
import com.sairo.be.domain.billing.service.PriceScheduleSelector.PriceSelection;
import com.sairo.be.global.security.StaffPrincipal;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
@Transactional(readOnly = true)
public class BillingPlanService {

  private final BillingContextService context;
  private final PriceScheduleSelector priceSelector;

  public BillingPlanService(BillingContextService context, PriceScheduleSelector priceSelector) {
    this.context = context;
    this.priceSelector = priceSelector;
  }

  public BillingPlanListResponse plans(StaffPrincipal principal) {
    Long officeId = principal.officeId();
    int memberCount = context.billableMembers(officeId);
    Instant reference = context.referenceTimeFor(officeId);
    PriceSelection selection = priceSelector.select(officeId, reference);
    return BillingMapper.toPlanList(selection, memberCount);
  }
}
