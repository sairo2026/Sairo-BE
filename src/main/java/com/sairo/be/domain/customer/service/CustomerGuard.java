package com.sairo.be.domain.customer.service;

import com.sairo.be.domain.customer.entity.Customer;
import com.sairo.be.domain.customer.repository.CustomerReadRepository;
import com.sairo.be.domain.customer.repository.CustomerRepository;
import com.sairo.be.domain.property.service.ManagingMemberDirectory;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class CustomerGuard {

  private final CustomerRepository customerRepository;
  private final CustomerReadRepository readRepository;
  private final ManagingMemberDirectory managingMemberDirectory;

  public CustomerGuard(
      CustomerRepository customerRepository,
      CustomerReadRepository readRepository,
      ManagingMemberDirectory managingMemberDirectory) {
    this.customerRepository = customerRepository;
    this.readRepository = readRepository;
    this.managingMemberDirectory = managingMemberDirectory;
  }

  public Customer lock(Long officeId, Long customerId) {
    return customerRepository
        .findForUpdate(customerId, officeId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  public Customer find(Long officeId, Long customerId) {
    return customerRepository
        .findByIdAndOfficeId(customerId, officeId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  public Customer lockFinal(Long officeId, Long customerId) {
    find(officeId, customerId);
    return lock(officeId, readRepository.finalCustomerId(customerId));
  }

  public void requireNotMerged(Long customerId) {
    if (readRepository.isMerged(customerId)) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
  }

  public void requireNotArchived(Customer customer) {
    if (customer.isArchived()) {
      throw new BusinessException(ErrorCode.CUSTOMER_ARCHIVED);
    }
  }

  public void requireVersion(Customer customer, long expectedVersion) {
    if (customer.getVersion() != expectedVersion) {
      throw BusinessException.versionConflict(customer.getVersion());
    }
  }

  public void validateManager(Long officeId, Long membershipId, String field) {
    ManagingMemberDirectory.ManagingMember member =
        managingMemberDirectory
            .find(membershipId)
            .filter(found -> found.officeId().equals(officeId))
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!member.canManageProperty()) {
      throw CustomerInput.invalid(
          field, FieldErrorCode.NOT_ALLOWED, "승인된 대표 또는 소속공인중개사만 지정할 수 있습니다.");
    }
  }

  public List<Long> mergedSet(Long finalCustomerId) {
    return readRepository.mergedSet(finalCustomerId);
  }
}
