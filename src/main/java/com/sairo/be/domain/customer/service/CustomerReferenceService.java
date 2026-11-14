package com.sairo.be.domain.customer.service;

import com.sairo.be.domain.customer.entity.Customer;
import com.sairo.be.domain.customer.repository.CustomerReadRepository;
import com.sairo.be.domain.customer.repository.CustomerRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
@Transactional(readOnly = true)
public class CustomerReferenceService {

  public record CustomerSummary(Long customerId, String name, boolean archived) {}

  private final CustomerGuard guard;
  private final CustomerReadRepository readRepository;
  private final CustomerRepository customerRepository;

  public CustomerReferenceService(
      CustomerGuard guard,
      CustomerReadRepository readRepository,
      CustomerRepository customerRepository) {
    this.guard = guard;
    this.readRepository = readRepository;
    this.customerRepository = customerRepository;
  }

  public CustomerSummary resolveFinal(Long officeId, Long customerId) {
    guard.find(officeId, customerId);
    Customer finalCustomer = guard.find(officeId, readRepository.finalCustomerId(customerId));
    return new CustomerSummary(
        finalCustomer.getId(), finalCustomer.getName(), finalCustomer.isArchived());
  }

  public Map<Long, String> names(Collection<Long> customerIds) {
    Map<Long, String> result = new LinkedHashMap<>();
    for (Customer customer : customerRepository.findAllById(customerIds)) {
      result.put(customer.getId(), customer.getName());
    }
    return result;
  }
}
