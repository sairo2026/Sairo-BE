package com.sairo.be.domain.customer.service;

import static com.sairo.be.domain.customer.service.CustomerInput.has;
import static com.sairo.be.domain.customer.service.CustomerInput.isNull;

import com.sairo.be.domain.customer.dto.response.CustomerCreateResponse;
import com.sairo.be.domain.customer.dto.response.CustomerDetailResponse;
import com.sairo.be.domain.customer.entity.Customer;
import com.sairo.be.domain.customer.entity.ProcessingBasis;
import com.sairo.be.domain.customer.repository.CustomerReadRepository;
import com.sairo.be.domain.customer.repository.CustomerRepository;
import com.sairo.be.domain.property.entity.EntityLifecycleEvent;
import com.sairo.be.domain.property.entity.LifecycleCreationSource;
import com.sairo.be.domain.property.repository.EntityLifecycleEventRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.port.NotificationPublisher;
import com.sairo.be.global.port.NotificationPublisher.Notice;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class CustomerCommandService {

  private static final Set<String> CREATE_KEYS =
      Set.of(
          "isCorporation",
          "name",
          "managingMembershipId",
          "dataSource",
          "processingBasis",
          "dataPurpose",
          "retentionUntil",
          "phone",
          "secondaryPhone",
          "email",
          "memo");
  private static final Set<String> UPDATE_KEYS =
      Set.of(
          "name",
          "managingMembershipId",
          "phone",
          "secondaryPhone",
          "email",
          "memo",
          "dataSource",
          "processingBasis",
          "dataPurpose",
          "retentionUntil");
  private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
  private static final int PHONE_MAX = 30;
  private static final int EMAIL_MAX = 254;
  static final String MANAGER_MESSAGE = "고객 담당자로 지정됐습니다.";

  private final CustomerRepository customerRepository;
  private final CustomerReadRepository readRepository;
  private final EntityLifecycleEventRepository lifecycleEventRepository;
  private final CustomerGuard guard;
  private final CustomerDetailAssembler assembler;
  private final NotificationPublisher notificationPublisher;
  private final Clock clock;

  public CustomerCommandService(
      CustomerRepository customerRepository,
      CustomerReadRepository readRepository,
      EntityLifecycleEventRepository lifecycleEventRepository,
      CustomerGuard guard,
      CustomerDetailAssembler assembler,
      NotificationPublisher notificationPublisher,
      Clock clock) {
    this.customerRepository = customerRepository;
    this.readRepository = readRepository;
    this.lifecycleEventRepository = lifecycleEventRepository;
    this.guard = guard;
    this.assembler = assembler;
    this.notificationPublisher = notificationPublisher;
    this.clock = clock;
  }

  @Transactional
  public CustomerCreateResponse create(StaffPrincipal principal, JsonNode body) {
    return register(principal, body, LifecycleCreationSource.MANUAL);
  }

  @Transactional
  public CustomerCreateResponse createImported(StaffPrincipal principal, JsonNode body) {
    return register(principal, body, LifecycleCreationSource.IMPORT);
  }

  private CustomerCreateResponse register(
      StaffPrincipal principal, JsonNode body, LifecycleCreationSource creationSource) {
    CustomerInput.requireObject(body, CREATE_KEYS);
    boolean corporation = CustomerInput.requiredBoolean(body, "isCorporation");
    String name = CustomerInput.requiredText(body, "name", 100, true);
    Long managerId = CustomerInput.requiredLong(body, "managingMembershipId");
    String dataSource = CustomerInput.requiredText(body, "dataSource", 100, true);
    ProcessingBasis basis = basis(body, true);
    String purpose = CustomerInput.requiredText(body, "dataPurpose", 200, true);
    LocalDate retention = CustomerInput.requiredDate(body, "retentionUntil");
    String phone = phone(body, "phone");
    String secondary = phone(body, "secondaryPhone");
    String email = email(body);
    String memo = CustomerInput.optionalText(body, "memo", 10_000, true);
    guard.validateManager(principal.officeId(), managerId, "managingMembershipId");
    Customer customer =
        Customer.register(
            principal.officeId(),
            corporation,
            name,
            managerId,
            dataSource,
            basis,
            purpose,
            retention);
    customer.changeContact(phone, secondary, email);
    customer.changeMemo(memo);
    Customer saved = customerRepository.saveAndFlush(customer);
    lifecycleEventRepository.save(
        EntityLifecycleEvent.customerCreated(
            principal.officeId(),
            saved.getId(),
            saved.getCreatedAt(),
            principal.membershipId(),
            creationSource));
    return new CustomerCreateResponse(
        saved.getId(),
        null,
        saved.getOfficeId(),
        TimeRules.toDisplayOffset(saved.getCreatedAt()),
        TimeRules.toDisplayOffset(saved.getUpdatedAt()),
        saved.getVersion(),
        saved.getProcessingBasis().name());
  }

  @Transactional
  public CustomerDetailResponse update(
      StaffPrincipal principal, Long customerId, long expectedVersion, JsonNode body) {
    CustomerInput.requireObject(body, UPDATE_KEYS);
    if (!body.propertyNames().iterator().hasNext()) {
      throw CustomerInput.invalid("body", FieldErrorCode.REQUIRED, "변경할 필드를 하나 이상 보내야 합니다.");
    }
    String name = has(body, "name") ? CustomerInput.requiredText(body, "name", 100, true) : null;
    Long managerId =
        has(body, "managingMembershipId")
            ? CustomerInput.requiredLong(body, "managingMembershipId")
            : null;
    String dataSource =
        has(body, "dataSource") ? CustomerInput.requiredText(body, "dataSource", 100, true) : null;
    ProcessingBasis basis = has(body, "processingBasis") ? basis(body, true) : null;
    String purpose =
        has(body, "dataPurpose")
            ? CustomerInput.requiredText(body, "dataPurpose", 200, true)
            : null;
    LocalDate retention =
        has(body, "retentionUntil") ? CustomerInput.requiredDate(body, "retentionUntil") : null;
    String phone = has(body, "phone") ? phone(body, "phone") : null;
    String secondary = has(body, "secondaryPhone") ? phone(body, "secondaryPhone") : null;
    String email = has(body, "email") ? email(body) : null;
    String memo = has(body, "memo") ? CustomerInput.optionalText(body, "memo", 10_000, true) : null;
    Customer customer = guard.lock(principal.officeId(), customerId);
    guard.requireNotMerged(customerId);
    guard.requireNotArchived(customer);
    guard.requireVersion(customer, expectedVersion);
    if (name != null) {
      customer.changeName(name);
    }
    if (managerId != null && !managerId.equals(customer.getManagingMembershipId())) {
      guard.validateManager(principal.officeId(), managerId, "managingMembershipId");
      customer.changeManager(managerId);
      if (!managerId.equals(principal.membershipId())) {
        notificationPublisher.publishToMembership(
            managerId,
            new Notice(
                principal.officeId(),
                null,
                "ASSIGNEE_CHANGED",
                MANAGER_MESSAGE,
                "CUSTOMER",
                customerId));
      }
    }
    if (has(body, "phone") || has(body, "secondaryPhone") || has(body, "email")) {
      customer.changeContact(
          has(body, "phone") ? phone : customer.getPhone(),
          has(body, "secondaryPhone") ? secondary : customer.getSecondaryPhone(),
          has(body, "email") ? email : customer.getEmail());
    }
    if (has(body, "memo")) {
      customer.changeMemo(memo);
    }
    customer.changeCollection(
        dataSource != null ? dataSource : customer.getDataSource(),
        basis != null ? basis : customer.getProcessingBasis(),
        purpose != null ? purpose : customer.getDataPurpose(),
        retention != null ? retention : customer.getRetentionUntil());
    return assembler.assemble(customerRepository.saveAndFlush(customer));
  }

  @Transactional
  public void delete(StaffPrincipal principal, Long customerId, Long expectedVersion) {
    Customer customer = guard.lock(principal.officeId(), customerId);
    guard.requireNotMerged(customerId);
    if (expectedVersion != null) {
      guard.requireVersion(customer, expectedVersion);
    }
    guard.requireNotArchived(customer);
    if (readRepository.isDeletable(readRepository.mergedSet(customerId))) {
      readRepository.deleteCustomerRows(customerId);
      return;
    }
    Instant now = clock.instant();
    customer.archive(now);
    lifecycleEventRepository.save(
        EntityLifecycleEvent.customerArchived(
            principal.officeId(), customerId, now, principal.membershipId()));
    customerRepository.saveAndFlush(customer);
  }

  @Transactional
  public CustomerDetailResponse restore(
      StaffPrincipal principal, Long customerId, Long expectedVersion) {
    Customer customer = guard.lock(principal.officeId(), customerId);
    guard.requireNotMerged(customerId);
    if (expectedVersion != null) {
      guard.requireVersion(customer, expectedVersion);
    }
    if (!customer.isArchived()) {
      throw new BusinessException(ErrorCode.CUSTOMER_NOT_ARCHIVED);
    }
    customer.restore();
    lifecycleEventRepository.save(
        EntityLifecycleEvent.customerRestored(
            principal.officeId(), customerId, clock.instant(), principal.membershipId()));
    return assembler.assemble(customerRepository.saveAndFlush(customer));
  }

  private static ProcessingBasis basis(JsonNode body, boolean required) {
    if (isNull(body, "processingBasis")) {
      if (required) {
        throw CustomerInput.invalid("processingBasis", FieldErrorCode.REQUIRED, "필수 입력입니다.");
      }
      return null;
    }
    ProcessingBasis basis = CustomerInput.enumValue(body, "processingBasis", ProcessingBasis.class);
    if (basis == ProcessingBasis.UNSPECIFIED) {
      throw CustomerInput.invalid(
          "processingBasis", FieldErrorCode.NOT_ALLOWED, "UNSPECIFIED는 새로 지정할 수 없습니다.");
    }
    return basis;
  }

  private static String phone(JsonNode body, String key) {
    String value = CustomerInput.optionalText(body, key, PHONE_MAX, true);
    if (value == null) {
      return null;
    }
    return com.sairo.be.global.support.PhoneNumbers.normalize(value).orElse(value);
  }

  private static String email(JsonNode body) {
    String value = CustomerInput.optionalText(body, "email", EMAIL_MAX, true);
    if (value != null && !EMAIL.matcher(value).matches()) {
      throw CustomerInput.invalid("email", FieldErrorCode.INVALID_FORMAT, "이메일 형식이 올바르지 않습니다.");
    }
    return value;
  }
}
