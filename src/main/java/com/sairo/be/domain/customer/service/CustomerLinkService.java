package com.sairo.be.domain.customer.service;

import static com.sairo.be.domain.customer.service.CustomerInput.has;

import com.sairo.be.domain.customer.dto.response.OwnerOccupantResponse;
import com.sairo.be.domain.customer.dto.response.PropertyInterestResponse;
import com.sairo.be.domain.customer.entity.Customer;
import com.sairo.be.domain.customer.entity.CustomerPropertyInterest;
import com.sairo.be.domain.customer.entity.OwnerRole;
import com.sairo.be.domain.customer.entity.PropertyOwnerOccupant;
import com.sairo.be.domain.customer.repository.CustomerPropertyInterestRepository;
import com.sairo.be.domain.customer.repository.CustomerRepository;
import com.sairo.be.domain.customer.repository.OwnerOccupantRepository;
import com.sairo.be.domain.property.entity.Property;
import com.sairo.be.domain.property.repository.PropertyRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class CustomerLinkService {

  private static final Set<String> OWNER_CREATE_KEYS =
      Set.of("customerId", "role", "startDate", "confirmedByMembershipId");
  private static final Set<String> OWNER_UPDATE_KEYS = Set.of("endDate");
  private static final Set<String> INTEREST_CREATE_KEYS =
      Set.of("propertyId", "startDate", "endDate", "note");
  private static final Set<String> INTEREST_UPDATE_KEYS = Set.of("endDate", "note");
  private static final int NOTE_MAX = 500;

  private final CustomerRepository customerRepository;
  private final OwnerOccupantRepository ownerOccupantRepository;
  private final CustomerPropertyInterestRepository interestRepository;
  private final PropertyRepository propertyRepository;
  private final CustomerGuard guard;
  private final Clock clock;

  public CustomerLinkService(
      CustomerRepository customerRepository,
      OwnerOccupantRepository ownerOccupantRepository,
      CustomerPropertyInterestRepository interestRepository,
      PropertyRepository propertyRepository,
      CustomerGuard guard,
      Clock clock) {
    this.customerRepository = customerRepository;
    this.ownerOccupantRepository = ownerOccupantRepository;
    this.interestRepository = interestRepository;
    this.propertyRepository = propertyRepository;
    this.guard = guard;
    this.clock = clock;
  }

  @Transactional
  public OwnerOccupantResponse createOwnerOccupant(
      StaffPrincipal principal, Long propertyId, JsonNode body) {
    CustomerInput.requireObject(body, OWNER_CREATE_KEYS);
    Long customerId = CustomerInput.requiredLong(body, "customerId");
    OwnerRole role = CustomerInput.requiredEnum(body, "role", OwnerRole.class);
    LocalDate startDate = CustomerInput.requiredDate(body, "startDate");
    Long confirmedBy = CustomerInput.optionalLong(body, "confirmedByMembershipId");
    Property property = requireProperty(principal.officeId(), propertyId);
    Customer customer = guard.lockFinal(principal.officeId(), customerId);
    guard.requireNotArchived(customer);
    Long confirmer = confirmedBy != null ? confirmedBy : principal.membershipId();
    guard.validateManager(principal.officeId(), confirmer, "confirmedByMembershipId");
    PropertyOwnerOccupant link =
        ownerOccupantRepository.saveAndFlush(
            PropertyOwnerOccupant.link(
                principal.officeId(),
                property.getId(),
                customer.getId(),
                role,
                startDate,
                confirmer,
                clock.instant()));
    customer.touch();
    return ownerResponse(link, customerRepository.saveAndFlush(customer));
  }

  @Transactional
  public OwnerOccupantResponse updateOwnerOccupant(
      StaffPrincipal principal, Long linkId, long expectedVersion, JsonNode body) {
    CustomerInput.requireObject(body, OWNER_UPDATE_KEYS);
    if (!has(body, "endDate")) {
      throw CustomerInput.invalid("endDate", FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    LocalDate endDate = CustomerInput.optionalDate(body, "endDate");
    PropertyOwnerOccupant link =
        ownerOccupantRepository
            .findByIdAndOfficeId(linkId, principal.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    Customer customer = guard.lockFinal(principal.officeId(), link.getCustomerId());
    guard.requireNotArchived(customer);
    guard.requireVersion(customer, expectedVersion);
    requireRange(link.getStartDate(), endDate);
    link.changeEndDate(endDate);
    ownerOccupantRepository.saveAndFlush(link);
    customer.touch();
    return ownerResponse(link, customerRepository.saveAndFlush(customer));
  }

  @Transactional
  public PropertyInterestResponse createInterest(
      StaffPrincipal principal, Long customerId, JsonNode body) {
    CustomerInput.requireObject(body, INTEREST_CREATE_KEYS);
    Long propertyId = CustomerInput.requiredLong(body, "propertyId");
    LocalDate startDate = CustomerInput.requiredDate(body, "startDate");
    LocalDate endDate = CustomerInput.optionalDate(body, "endDate");
    String note = CustomerInput.optionalText(body, "note", NOTE_MAX, false);
    requireRange(startDate, endDate);
    Customer customer = guard.lockFinal(principal.officeId(), customerId);
    guard.requireNotArchived(customer);
    Property property = requireProperty(principal.officeId(), propertyId);
    CustomerPropertyInterest interest =
        interestRepository.saveAndFlush(
            CustomerPropertyInterest.open(
                principal.officeId(),
                customer.getId(),
                property.getId(),
                startDate,
                endDate,
                note));
    customer.touch();
    return interestResponse(interest, customerRepository.saveAndFlush(customer));
  }

  @Transactional
  public PropertyInterestResponse updateInterest(
      StaffPrincipal principal, Long interestId, long expectedVersion, JsonNode body) {
    CustomerInput.requireObject(body, INTEREST_UPDATE_KEYS);
    if (!body.propertyNames().iterator().hasNext()) {
      throw CustomerInput.invalid("body", FieldErrorCode.REQUIRED, "변경할 필드를 하나 이상 보내야 합니다.");
    }
    LocalDate endDate = has(body, "endDate") ? CustomerInput.optionalDate(body, "endDate") : null;
    String note =
        has(body, "note") ? CustomerInput.optionalText(body, "note", NOTE_MAX, false) : null;
    CustomerPropertyInterest interest =
        interestRepository
            .findByIdAndOfficeId(interestId, principal.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    Customer customer = guard.lockFinal(principal.officeId(), interest.getCustomerId());
    guard.requireNotArchived(customer);
    guard.requireVersion(customer, expectedVersion);
    LocalDate finalEnd = has(body, "endDate") ? endDate : interest.getEndDate();
    requireRange(interest.getStartDate(), finalEnd);
    interest.change(finalEnd, has(body, "note") ? note : interest.getNote());
    interestRepository.saveAndFlush(interest);
    customer.touch();
    return interestResponse(interest, customerRepository.saveAndFlush(customer));
  }

  private Property requireProperty(Long officeId, Long propertyId) {
    Property property =
        propertyRepository
            .findByIdAndOfficeId(propertyId, officeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (property.isArchived()) {
      throw new BusinessException(ErrorCode.PROPERTY_ARCHIVED);
    }
    return property;
  }

  private static void requireRange(LocalDate start, LocalDate end) {
    if (end != null && end.isBefore(start)) {
      throw new BusinessException(ErrorCode.INVALID_RANGE);
    }
  }

  private static OwnerOccupantResponse ownerResponse(
      PropertyOwnerOccupant link, Customer customer) {
    return new OwnerOccupantResponse(
        link.getId(),
        link.getOfficeId(),
        link.getPropertyId(),
        link.getCustomerId(),
        link.getRole().name(),
        link.getStartDate(),
        link.getEndDate(),
        link.getConfirmedByMembershipId(),
        TimeRules.toDisplayOffset(link.getConfirmedAt()),
        TimeRules.toDisplayOffset(link.getCreatedAt()),
        TimeRules.toDisplayOffset(customer.getUpdatedAt()),
        customer.getVersion());
  }

  private static PropertyInterestResponse interestResponse(
      CustomerPropertyInterest interest, Customer customer) {
    return new PropertyInterestResponse(
        interest.getId(),
        interest.getOfficeId(),
        interest.getCustomerId(),
        interest.getPropertyId(),
        interest.getStartDate(),
        interest.getEndDate(),
        interest.getNote(),
        TimeRules.toDisplayOffset(interest.getCreatedAt()),
        TimeRules.toDisplayOffset(customer.getUpdatedAt()),
        customer.getVersion());
  }
}
