package com.sairo.be.domain.customer.service;

import static com.sairo.be.domain.customer.service.CustomerInput.has;

import com.sairo.be.domain.customer.dto.response.DemandConditionResponse;
import com.sairo.be.domain.customer.entity.Customer;
import com.sairo.be.domain.customer.entity.CustomerDemandCondition;
import com.sairo.be.domain.customer.repository.CustomerDemandConditionRepository;
import com.sairo.be.domain.customer.repository.CustomerReadRepository;
import com.sairo.be.domain.customer.repository.CustomerRepository;
import com.sairo.be.domain.property.entity.DealType;
import com.sairo.be.domain.property.repository.PropertyCategoryRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.InputRules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class DemandConditionService {

  private static final Set<String> KEYS =
      Set.of(
          "dealType",
          "categoryId",
          "priceMin",
          "priceMax",
          "depositMin",
          "depositMax",
          "rentMin",
          "rentMax",
          "areaMin",
          "areaMax",
          "moveInDate",
          "regions");
  private static final int REGION_MAX = 20;

  private final CustomerRepository customerRepository;
  private final CustomerDemandConditionRepository conditionRepository;
  private final CustomerReadRepository readRepository;
  private final PropertyCategoryRepository categoryRepository;
  private final CustomerGuard guard;
  private final CustomerDetailAssembler assembler;

  public DemandConditionService(
      CustomerRepository customerRepository,
      CustomerDemandConditionRepository conditionRepository,
      CustomerReadRepository readRepository,
      PropertyCategoryRepository categoryRepository,
      CustomerGuard guard,
      CustomerDetailAssembler assembler) {
    this.customerRepository = customerRepository;
    this.conditionRepository = conditionRepository;
    this.readRepository = readRepository;
    this.categoryRepository = categoryRepository;
    this.guard = guard;
    this.assembler = assembler;
  }

  @Transactional
  public DemandConditionResponse create(StaffPrincipal principal, Long customerId, JsonNode body) {
    CustomerInput.requireObject(body, KEYS);
    CustomerInput.requiredEnum(body, "dealType", DealType.class);
    CustomerDemandCondition.Values values = values(body, null);
    List<String> regions = regions(body);
    Customer customer = guard.lockFinal(principal.officeId(), customerId);
    guard.requireNotArchived(customer);
    requireCategory(principal.officeId(), values.categoryId());
    CustomerDemandCondition saved =
        conditionRepository.saveAndFlush(CustomerDemandCondition.create(customer.getId(), values));
    readRepository.replaceRegions(saved.getId(), regions == null ? List.of() : regions);
    customer.touch();
    return response(customerRepository.saveAndFlush(customer), saved.getId());
  }

  @Transactional
  public DemandConditionResponse update(
      StaffPrincipal principal, Long conditionId, long expectedVersion, JsonNode body) {
    CustomerInput.requireObject(body, KEYS);
    if (!body.propertyNames().iterator().hasNext()) {
      throw CustomerInput.invalid("body", FieldErrorCode.REQUIRED, "변경할 필드를 하나 이상 보내야 합니다.");
    }
    CustomerDemandCondition condition =
        conditionRepository
            .findById(conditionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    Customer customer = guard.lockFinal(principal.officeId(), condition.getCustomerId());
    guard.requireNotArchived(customer);
    guard.requireVersion(customer, expectedVersion);
    CustomerDemandCondition.Values merged = values(body, condition.values());
    List<String> regions = has(body, "regions") ? regions(body) : null;
    requireCategory(principal.officeId(), merged.categoryId());
    condition.apply(merged);
    conditionRepository.saveAndFlush(condition);
    if (has(body, "regions")) {
      readRepository.replaceRegions(conditionId, regions == null ? List.of() : regions);
    }
    customer.touch();
    return response(customerRepository.saveAndFlush(customer), conditionId);
  }

  private DemandConditionResponse response(Customer customer, Long conditionId) {
    CustomerReadRepository.ConditionRow row =
        readRepository.findCondition(conditionId).orElseThrow();
    return assembler.conditionResponse(
        customer, row, readRepository.findRegions(List.of(conditionId)));
  }

  private void requireCategory(Long officeId, Long categoryId) {
    if (categoryId == null) {
      return;
    }
    categoryRepository
        .findById(categoryId)
        .filter(category -> category.isVisibleTo(officeId))
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  private static CustomerDemandCondition.Values values(
      JsonNode body, CustomerDemandCondition.Values base) {
    boolean create = base == null;
    String dealType =
        has(body, "dealType")
            ? CustomerInput.requiredEnum(body, "dealType", DealType.class).name()
            : create ? null : base.dealType();
    Long categoryId =
        has(body, "categoryId")
            ? CustomerInput.optionalLong(body, "categoryId")
            : create ? null : base.categoryId();
    Long priceMin = amount(body, "priceMin", create, base == null ? null : base.priceMin());
    Long priceMax = amount(body, "priceMax", create, base == null ? null : base.priceMax());
    Long depositMin = amount(body, "depositMin", create, base == null ? null : base.depositMin());
    Long depositMax = amount(body, "depositMax", create, base == null ? null : base.depositMax());
    Long rentMin = amount(body, "rentMin", create, base == null ? null : base.rentMin());
    Long rentMax = amount(body, "rentMax", create, base == null ? null : base.rentMax());
    BigDecimal areaMin =
        has(body, "areaMin")
            ? CustomerInput.optionalArea(body, "areaMin")
            : create ? null : base.areaMin();
    BigDecimal areaMax =
        has(body, "areaMax")
            ? CustomerInput.optionalArea(body, "areaMax")
            : create ? null : base.areaMax();
    LocalDate moveIn =
        has(body, "moveInDate")
            ? CustomerInput.optionalDate(body, "moveInDate")
            : create ? null : base.moveInDate();
    requireOrdered(priceMin, priceMax);
    requireOrdered(depositMin, depositMax);
    requireOrdered(rentMin, rentMax);
    if (areaMin != null && areaMax != null && areaMin.compareTo(areaMax) > 0) {
      throw new BusinessException(ErrorCode.INVALID_RANGE);
    }
    return new CustomerDemandCondition.Values(
        dealType,
        categoryId,
        priceMin,
        priceMax,
        depositMin,
        depositMax,
        rentMin,
        rentMax,
        areaMin,
        areaMax,
        moveIn);
  }

  private static Long amount(JsonNode body, String key, boolean create, Long base) {
    if (has(body, key)) {
      return CustomerInput.optionalAmount(body, key);
    }
    return create ? null : base;
  }

  private static void requireOrdered(Long min, Long max) {
    if (min != null && max != null && min > max) {
      throw new BusinessException(ErrorCode.INVALID_RANGE);
    }
  }

  private static List<String> regions(JsonNode body) {
    if (!has(body, "regions") || body.get("regions").isNull()) {
      return null;
    }
    JsonNode node = body.get("regions");
    if (!node.isArray()) {
      throw CustomerInput.invalid("regions", FieldErrorCode.TYPE_MISMATCH, "배열이어야 합니다.");
    }
    Set<String> unique = new LinkedHashSet<>();
    for (JsonNode item : node) {
      if (!item.isString()) {
        throw CustomerInput.invalid("regions", FieldErrorCode.TYPE_MISMATCH, "문자열 배열이어야 합니다.");
      }
      unique.add(InputRules.requireText("regions", item.asString(), 1, 100));
    }
    if (unique.size() > REGION_MAX) {
      throw CustomerInput.invalid("regions", FieldErrorCode.OUT_OF_RANGE, "희망 지역은 20개까지입니다.");
    }
    return new ArrayList<>(unique);
  }
}
