package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.customer.service.CustomerImportService;
import com.sairo.be.domain.migration.entity.ImportRecordLink;
import com.sairo.be.domain.migration.entity.ImportSkipReason;
import com.sairo.be.domain.migration.entity.ImportTargetType;
import com.sairo.be.domain.migration.repository.ImportRecordLinkRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class ImportDuplicateClassifier {

  private static final int LOOKUP_CHUNK = 500;

  public record Judgement(ImportSkipReason reason, Long matchedEntityId, boolean matchedArchived) {

    static final Judgement NEW = new Judgement(null, null, false);

    public boolean skipped() {
      return reason != null;
    }
  }

  private record ExistingCustomer(long customerId, boolean archived) {}

  private final ImportRecordLinkRepository linkRepository;
  private final CustomerImportService customerImportService;

  public ImportDuplicateClassifier(
      ImportRecordLinkRepository linkRepository, CustomerImportService customerImportService) {
    this.linkRepository = linkRepository;
    this.customerImportService = customerImportService;
  }

  public Session open(
      Long officeId, ImportTargetType type, String sourceSystem, List<NormalizedRow> rows) {
    Map<String, Long> linked = loadLinked(officeId, type, sourceSystem, rows);
    Map<String, ExistingCustomer> existing =
        type == ImportTargetType.CUSTOMER ? loadExistingCustomers(officeId, rows) : Map.of();
    return new Session(type, linked, existing);
  }

  private static String customerKey(String name, String phoneDigits) {
    return name + "|" + phoneDigits;
  }

  private static String compact(Object value) {
    return value == null ? "" : value.toString().replaceAll("\\s+", "").toLowerCase();
  }

  private static String digits(Object value) {
    return value == null ? "" : value.toString().replaceAll("\\D", "");
  }

  private static Set<String> customerKeys(NormalizedRow row) {
    String name = compact(row.values().get(ImportTargetFields.CUSTOMER_NAME));
    Set<String> keys = new LinkedHashSet<>();
    if (name.isEmpty()) {
      return keys;
    }
    for (String field :
        List.of(ImportTargetFields.CUSTOMER_PHONE, ImportTargetFields.CUSTOMER_SECONDARY_PHONE)) {
      String phone = digits(row.values().get(field));
      if (!phone.isEmpty()) {
        keys.add(customerKey(name, phone));
      }
    }
    return keys;
  }

  private Map<String, Long> loadLinked(
      Long officeId, ImportTargetType type, String sourceSystem, List<NormalizedRow> rows) {
    Set<String> ids = new LinkedHashSet<>();
    for (NormalizedRow row : rows) {
      if (row.sourceId() != null) {
        ids.add(row.sourceId());
      }
    }
    Map<String, Long> linked = new HashMap<>();
    List<String> idList = new ArrayList<>(ids);
    for (int start = 0; start < idList.size(); start += LOOKUP_CHUNK) {
      List<String> chunk = idList.subList(start, Math.min(start + LOOKUP_CHUNK, idList.size()));
      for (ImportRecordLink link :
          linkRepository.findByOfficeIdAndSourceSystemAndSourceTypeAndSourceIdIn(
              officeId, sourceSystem, type, chunk)) {
        linked.put(link.getSourceId(), link.getTargetEntityId());
      }
    }
    return linked;
  }

  private Map<String, ExistingCustomer> loadExistingCustomers(
      Long officeId, List<NormalizedRow> rows) {
    Set<String> names = new LinkedHashSet<>();
    for (NormalizedRow row : rows) {
      if (row.hasError() || customerKeys(row).isEmpty()) {
        continue;
      }
      names.add(compact(row.values().get(ImportTargetFields.CUSTOMER_NAME)));
    }
    Map<String, ExistingCustomer> existing = new HashMap<>();
    List<String> nameList = new ArrayList<>(names);
    for (int start = 0; start < nameList.size(); start += LOOKUP_CHUNK) {
      for (CustomerImportService.ExistingMatch match :
          customerImportService.findExistingMatches(
              officeId, nameList.subList(start, Math.min(start + LOOKUP_CHUNK, nameList.size())))) {
        for (String phone : List.of(match.phoneDigits(), match.secondaryPhoneDigits())) {
          if (!phone.isEmpty()) {
            existing.putIfAbsent(
                customerKey(match.normalizedName(), phone),
                new ExistingCustomer(match.customerId(), match.archived()));
          }
        }
      }
    }
    return existing;
  }

  public static final class Session {

    private final ImportTargetType type;
    private final Map<String, Long> linked;
    private final Map<String, ExistingCustomer> existing;
    private final Set<String> createdIds = new HashSet<>();
    private final Set<String> createdKeys = new HashSet<>();

    private Session(
        ImportTargetType type, Map<String, Long> linked, Map<String, ExistingCustomer> existing) {
      this.type = type;
      this.linked = linked;
      this.existing = existing;
    }

    public Judgement judge(NormalizedRow row) {
      if (row.hasError()) {
        return Judgement.NEW;
      }
      String id = row.sourceId();
      if (id != null && linked.containsKey(id)) {
        return new Judgement(ImportSkipReason.ALREADY_IMPORTED, linked.get(id), false);
      }
      if (id != null && createdIds.contains(id)) {
        return new Judgement(ImportSkipReason.DUPLICATE_IN_FILE, null, false);
      }
      if (type == ImportTargetType.CUSTOMER) {
        Set<String> keys = customerKeys(row);
        for (String key : keys) {
          ExistingCustomer match = existing.get(key);
          if (match != null) {
            return new Judgement(
                ImportSkipReason.DUPLICATE_EXISTING, match.customerId(), match.archived());
          }
        }
        for (String key : keys) {
          if (createdKeys.contains(key)) {
            return new Judgement(ImportSkipReason.DUPLICATE_IN_FILE, null, false);
          }
        }
      }
      return Judgement.NEW;
    }

    public void markCreated(NormalizedRow row) {
      if (row.sourceId() != null) {
        createdIds.add(row.sourceId());
      }
      if (type == ImportTargetType.CUSTOMER) {
        createdKeys.addAll(customerKeys(row));
      }
    }
  }
}
