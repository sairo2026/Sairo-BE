package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.visit.entity.PropertyAccessApproval;
import com.sairo.be.domain.visit.entity.VisitApproverCondition;
import com.sairo.be.domain.visit.entity.VisitCandidateProperty;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.entity.VisitCustomerLink;
import com.sairo.be.domain.visit.repository.ApproverConditionRowRepository;
import com.sairo.be.domain.visit.repository.ConfirmedVisitRepository;
import com.sairo.be.domain.visit.repository.ScheduleBusyRepository;
import com.sairo.be.domain.visit.repository.VisitCandidatePropertyRepository;
import com.sairo.be.domain.visit.repository.VisitCustomerLinkRepository;
import com.sairo.be.domain.visit.repository.VisitCustomerTimeSlotRepository;
import com.sairo.be.domain.visit.service.VisitFeasibility.Context;
import com.sairo.be.domain.visit.service.VisitRequestChecker.Slot;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class FeasibilityContextFactory {

  private final ApproverConditionRowRepository rowRepository;
  private final ConfirmedVisitRepository confirmedVisits;
  private final ScheduleBusyRepository busyRepository;
  private final VisitCustomerLinkRepository linkRepository;
  private final VisitCustomerTimeSlotRepository slotRepository;
  private final VisitCandidatePropertyRepository candidateRepository;

  public FeasibilityContextFactory(
      ApproverConditionRowRepository rowRepository,
      ConfirmedVisitRepository confirmedVisits,
      ScheduleBusyRepository busyRepository,
      VisitCustomerLinkRepository linkRepository,
      VisitCustomerTimeSlotRepository slotRepository,
      VisitCandidatePropertyRepository candidateRepository) {
    this.rowRepository = rowRepository;
    this.confirmedVisits = confirmedVisits;
    this.busyRepository = busyRepository;
    this.linkRepository = linkRepository;
    this.slotRepository = slotRepository;
    this.candidateRepository = candidateRepository;
  }

  public ConditionSpec specOf(VisitApproverCondition condition) {
    return new ConditionSpec(
        condition.getAppliesFrom(),
        condition.getAppliesUntil(),
        condition.getMaxVisitsPerDay(),
        condition.getMinGapMinutes(),
        condition.getMinLeadMinutes(),
        condition.getSingleVisitMaxMinutes(),
        condition.getGroupingPreference(),
        rowRepository.windowsOf(condition.getId()),
        rowRepository.dateRulesOf(condition.getId()));
  }

  public List<Slot> customerSlots(Long coordinationId) {
    Optional<VisitCustomerLink> link =
        linkRepository.findFirstByCoordinationIdOrderByIdDesc(coordinationId);
    return link.filter(found -> found.getResponseVersion() > 0)
        .map(
            found ->
                slotRepository
                    .findByCustomerLinkIdAndResponseVersionOrderByStartsAtAscIdAsc(
                        found.getId(), found.getResponseVersion())
                    .stream()
                    .map(slot -> new Slot(slot.getStartsAt(), slot.getEndsAt()))
                    .toList())
        .orElse(List.of());
  }

  public Set<Long> customerGroup(VisitCoordination coordination) {
    return busyRepository.customerGroup(busyRepository.rootCustomer(coordination.getCustomerId()));
  }

  public Context build(
      VisitCoordination coordination,
      VisitCandidateProperty candidate,
      PropertyAccessApproval approval,
      VisitApproverCondition condition,
      Long membershipId) {
    List<VisitCandidateProperty> all =
        candidateRepository.findByCoordinationIdOrderBySortOrderAscIdAsc(coordination.getId());
    boolean last = all.isEmpty() || all.get(all.size() - 1).getId().equals(candidate.getId());
    return new Context(
        specOf(condition),
        approval.getValidFrom(),
        approval.getValidUntil(),
        customerSlots(coordination.getId()),
        confirmedVisits.findByApproval(approval.getId()),
        busyRepository.forManager(coordination.getManagerMembershipId()),
        busyRepository.forCustomers(customerGroup(coordination)),
        busyRepository.forProperty(candidate.getPropertyId()),
        coordination.getId(),
        membershipId,
        last ? 0 : candidate.getTravelDurationMinutes());
  }
}
