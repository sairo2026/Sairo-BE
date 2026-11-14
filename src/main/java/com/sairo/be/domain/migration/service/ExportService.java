package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.migration.dto.request.ExportCreateRequest;
import com.sairo.be.domain.migration.dto.request.ExportPolicyUpdateRequest;
import com.sairo.be.domain.migration.dto.response.ExportAllowedTablesResponse;
import com.sairo.be.domain.migration.dto.response.ExportCreateResponse;
import com.sairo.be.domain.migration.dto.response.ExportListItem;
import com.sairo.be.domain.migration.dto.response.ExportResultResponse;
import com.sairo.be.domain.migration.repository.ExportJobJdbcRepository;
import com.sairo.be.domain.migration.repository.ExportJobJdbcRepository.Row;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.ObjectStorage;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StaffRole;
import com.sairo.be.global.security.StepUpState;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.StatusFilter;
import com.sairo.be.global.support.TimeRules;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class ExportService {

  public static final Duration FILE_RETENTION = Duration.ofHours(24);
  public static final Duration DOWNLOAD_URL_VALIDITY = Duration.ofMinutes(10);

  public enum Status {
    PENDING,
    COMPLETED,
    FAILED
  }

  private final ExportJobJdbcRepository jobRepository;
  private final ExportDispatcher dispatcher;
  private final ObjectStorage objectStorage;
  private final StepUpState stepUpState;
  private final AdvisoryLock advisoryLock;
  private final Clock clock;

  public ExportService(
      ExportJobJdbcRepository jobRepository,
      ExportDispatcher dispatcher,
      ObjectStorage objectStorage,
      StepUpState stepUpState,
      AdvisoryLock advisoryLock,
      Clock clock) {
    this.jobRepository = jobRepository;
    this.dispatcher = dispatcher;
    this.objectStorage = objectStorage;
    this.stepUpState = stepUpState;
    this.advisoryLock = advisoryLock;
    this.clock = clock;
  }

  @Transactional
  public ExportCreateResponse create(StaffPrincipal principal, HttpSession session, JsonNode body) {
    ExportCreateRequest request = ImportSupportRequestParser.exportCreate(body);
    boolean owner = principal.role() == StaffRole.OWNER;
    if (request.scope().equals("FULL")) {
      if (!owner) {
        throw new BusinessException(ErrorCode.FORBIDDEN);
      }
      stepUpState.require(session);
    } else if (!owner) {
      requireAllowed(principal.officeId(), request.tables());
    }
    Instant created = now();
    long id =
        jobRepository.insert(
            principal.officeId(),
            principal.membershipId(),
            request.scope(),
            request.scope().equals("FULL") ? null : request.tables(),
            created,
            created.plus(FILE_RETENTION));
    dispatcher.dispatch(id);
    return new ExportCreateResponse(
        id,
        Status.PENDING.name(),
        null,
        null,
        TimeRules.toDisplayOffset(created.plus(FILE_RETENTION)));
  }

  @Transactional(readOnly = true)
  public ExportResultResponse get(StaffPrincipal principal, Long exportId) {
    Row job =
        jobRepository
            .findByIdAndOffice(exportId, principal.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    boolean owner = principal.role() == StaffRole.OWNER;
    if (!owner) {
      if (job.createdByMembershipId() != principal.membershipId() || job.scope().equals("FULL")) {
        throw new BusinessException(ErrorCode.FORBIDDEN);
      }
      requireAllowed(principal.officeId(), job.tableCodes());
    }
    Instant now = clock.instant();
    if (!now.isBefore(job.fileExpiresAt())) {
      throw new BusinessException(ErrorCode.EXPORT_EXPIRED);
    }
    String url = null;
    Instant urlExpiresAt = null;
    if (job.status().equals(Status.COMPLETED.name())) {
      if (job.fileObjectKey() == null) {
        throw new BusinessException(ErrorCode.EXPORT_EXPIRED);
      }
      try {
        url = objectStorage.issueDownloadUrl(job.fileObjectKey(), DOWNLOAD_URL_VALIDITY).toString();
      } catch (IllegalArgumentException e) {
        throw new BusinessException(ErrorCode.EXPORT_EXPIRED);
      }
      urlExpiresAt = now.plus(DOWNLOAD_URL_VALIDITY);
    }
    return new ExportResultResponse(
        job.id(),
        job.status(),
        url,
        TimeRules.toDisplayOffset(urlExpiresAt),
        TimeRules.toDisplayOffset(job.fileExpiresAt()),
        job.failureReason());
  }

  @Transactional(readOnly = true)
  public PageResponse<ExportListItem> list(
      StaffPrincipal principal, String status, Integer page, Integer size) {
    PageQuery pageQuery = PageQuery.of(page, size);
    Set<Status> statuses = StatusFilter.parse(status, Status.class, EnumSet.allOf(Status.class));
    List<String> names = statuses.stream().map(Status::name).toList();
    Long creator = principal.role() == StaffRole.OWNER ? null : principal.membershipId();
    Instant now = clock.instant();
    List<Row> rows =
        jobRepository.list(
            principal.officeId(),
            creator,
            names,
            now,
            pageQuery.page() * pageQuery.size(),
            pageQuery.size());
    long total = jobRepository.count(principal.officeId(), creator, names, now);
    return PageResponse.of(
        rows.stream()
            .map(
                row ->
                    new ExportListItem(
                        row.id(),
                        row.scope(),
                        row.status(),
                        TimeRules.toDisplayOffset(row.createdAt()),
                        TimeRules.toDisplayOffset(row.fileExpiresAt())))
            .toList(),
        pageQuery.page(),
        pageQuery.size(),
        total);
  }

  @Transactional(readOnly = true)
  public ExportAllowedTablesResponse allowedTables(StaffPrincipal principal) {
    return tablesFor(principal.role() == StaffRole.OWNER, principal.officeId());
  }

  @Transactional
  public ExportAllowedTablesResponse updatePolicy(StaffPrincipal principal, JsonNode body) {
    ExportPolicyUpdateRequest request = ImportSupportRequestParser.exportPolicy(body);
    advisoryLock.lockForTransaction("export-policy:" + principal.officeId());
    jobRepository.replaceAllowedTables(principal.officeId(), request.allowedTables());
    return tablesFor(true, principal.officeId());
  }

  private ExportAllowedTablesResponse tablesFor(boolean owner, Long officeId) {
    Set<String> allowed = jobRepository.findAllowedTables(officeId);
    return new ExportAllowedTablesResponse(
        Arrays.stream(ExportTables.values())
            .filter(table -> owner || allowed.contains(table.name()))
            .map(
                table ->
                    new ExportAllowedTablesResponse.Table(
                        table.name(), table.label(), allowed.contains(table.name())))
            .toList());
  }

  private void requireAllowed(Long officeId, List<String> tables) {
    if (!jobRepository.findAllowedTables(officeId).containsAll(tables)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
