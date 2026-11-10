package com.sairo.be.domain.customer.service;

import com.sairo.be.domain.customer.dto.response.CandidatePropertyResponse;
import com.sairo.be.domain.customer.dto.response.CustomerAssistantViewResponse;
import com.sairo.be.domain.customer.dto.response.CustomerDetailResponse;
import com.sairo.be.domain.customer.dto.response.CustomerListItemResponse;
import com.sairo.be.domain.customer.dto.response.DuplicateCustomerCheckResponse;
import com.sairo.be.domain.customer.entity.Customer;
import com.sairo.be.domain.customer.repository.CustomerReadRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.port.AssistantAssignments;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StaffRole;
import com.sairo.be.global.support.InputRules;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
@Transactional(readOnly = true)
public class CustomerQueryService {

  private static final Set<String> ROLES = Set.of("DEMAND", "OWNER", "OCCUPANT", "OTHER");

  private final CustomerGuard guard;
  private final CustomerReadRepository readRepository;
  private final CustomerDetailAssembler assembler;
  private final AssistantAssignments assistantAssignments;
  private final Clock clock;

  public CustomerQueryService(
      CustomerGuard guard,
      CustomerReadRepository readRepository,
      CustomerDetailAssembler assembler,
      AssistantAssignments assistantAssignments,
      Clock clock) {
    this.guard = guard;
    this.readRepository = readRepository;
    this.assembler = assembler;
    this.assistantAssignments = assistantAssignments;
    this.clock = clock;
  }

  public PageResponse<CustomerListItemResponse> list(
      StaffPrincipal principal, String keyword, String role, Boolean archived, PageQuery page) {
    String normalizedKeyword = keyword == null ? null : keyword.strip();
    if (normalizedKeyword != null && normalizedKeyword.isEmpty()) {
      normalizedKeyword = null;
    }
    if (normalizedKeyword != null && InputRules.length(normalizedKeyword) > InputRules.NAME_MAX) {
      throw CustomerInput.invalid("keyword", FieldErrorCode.TOO_LONG, "검색어가 너무 깁니다.");
    }
    if (role != null && !ROLES.contains(role)) {
      throw CustomerInput.invalid("role", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 값입니다.");
    }
    CustomerReadRepository.ListFilter filter =
        new CustomerReadRepository.ListFilter(
            normalizedKeyword, role, Boolean.TRUE.equals(archived));
    LocalDate today = TimeRules.displayDate(clock.instant());
    long total = readRepository.countList(principal.officeId(), filter);
    List<CustomerReadRepository.ListRow> rows =
        readRepository.findListRows(
            principal.officeId(), filter, today, page.size(), (long) page.page() * page.size());
    Map<Long, CustomerReadRepository.RoleFlags> roles =
        readRepository.findRoles(
            principal.officeId(),
            rows.stream().map(CustomerReadRepository.ListRow::customerId).toList());
    List<CustomerListItemResponse> items =
        rows.stream()
            .map(
                row ->
                    new CustomerListItemResponse(
                        row.customerId(),
                        row.name(),
                        row.corporation(),
                        row.phone(),
                        CustomerDetailAssembler.rolesOf(roles.get(row.customerId())),
                        TimeRules.toDisplayOffset(row.deletedAt()),
                        TimeRules.toDisplayOffset(row.updatedAt()),
                        row.officeId(),
                        TimeRules.toDisplayOffset(row.createdAt()),
                        row.version()))
            .toList();
    return PageResponse.of(items, page.page(), page.size(), total);
  }

  public CustomerDetailResponse detail(StaffPrincipal principal, Long customerId) {
    Customer customer = guard.find(principal.officeId(), customerId);
    return assembler.assemble(customer);
  }

  public DuplicateCustomerCheckResponse duplicateCandidates(
      StaffPrincipal principal, String name, String phone) {
    String normalizedName = InputRules.requireText("name", name, 1, InputRules.NAME_MAX);
    String normalizedPhone = InputRules.requireText("phone", phone, 1, 30);
    List<CustomerReadRepository.DuplicateRow> rows =
        readRepository.findDuplicates(principal.officeId(), normalizedName, normalizedPhone);
    Map<Long, CustomerReadRepository.RoleFlags> roles =
        readRepository.findRoles(
            principal.officeId(),
            rows.stream().map(CustomerReadRepository.DuplicateRow::customerId).toList());
    return new DuplicateCustomerCheckResponse(
        rows.stream()
            .map(
                row ->
                    new DuplicateCustomerCheckResponse.Item(
                        row.customerId(),
                        row.name(),
                        row.corporation(),
                        row.phone(),
                        CustomerDetailAssembler.rolesOf(roles.get(row.customerId())),
                        TimeRules.toDisplayOffset(row.deletedAt()),
                        row.managingMembershipId()))
            .toList());
  }

  public CustomerAssistantViewResponse assistantView(StaffPrincipal principal, Long customerId) {
    Customer customer = guard.find(principal.officeId(), customerId);
    if (principal.role() == StaffRole.DEPUTY
        && !assistantAssignments.isAssignedCustomer(
            principal.officeId(), principal.membershipId(), customerId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    return new CustomerAssistantViewResponse(
        customer.getId(), customer.getName(), customer.getPhone());
  }

  public CandidatePropertyResponse candidateProperties(StaffPrincipal principal, Long conditionId) {
    CustomerReadRepository.ConditionRow condition =
        readRepository
            .findCondition(conditionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    guard.find(principal.officeId(), condition.customerId());
    List<String> regions =
        readRepository.findRegions(List.of(conditionId)).stream()
            .map(CustomerReadRepository.RegionRow::regionName)
            .toList();
    return new CandidatePropertyResponse(
        readRepository.findCandidateProperties(principal.officeId(), condition, regions).stream()
            .map(
                row ->
                    new CandidatePropertyResponse.Item(
                        row.propertyId(),
                        row.name(),
                        row.dealType(),
                        row.depositAmount(),
                        row.salePrice(),
                        row.monthlyRentAmount()))
            .toList());
  }
}
