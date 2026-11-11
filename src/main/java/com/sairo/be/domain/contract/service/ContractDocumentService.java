package com.sairo.be.domain.contract.service;

import com.sairo.be.domain.contract.dto.response.ContractDocumentResponse;
import com.sairo.be.domain.contract.entity.ContractRow;
import com.sairo.be.domain.contract.repository.ContractDocumentRepository;
import com.sairo.be.domain.contract.repository.ContractDocumentRepository.DocumentRow;
import com.sairo.be.domain.contract.repository.ContractDocumentRepository.NewDocument;
import com.sairo.be.domain.contract.repository.ContractHistoryRepository;
import com.sairo.be.domain.contract.repository.ContractHistoryRepository.RevisionRow;
import com.sairo.be.domain.contract.repository.ContractRepository;
import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.customer.service.SensitiveDataDetector;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.port.ObjectStorage;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.TimeRules;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 계약 서류 업로드·정정본·다운로드 링크다(CONTRACT-04). 체결 뒤에만 올리고, 제출한 계약 핵심정보 버전이 최신이어야 한다. 보존 종료일은 실제 작성일과 교부일 중
 * 늦은 날에 종류별 연수를 더한 날이고 날짜가 하나라도 없으면 사유를 받아 비워 둔다. 정정본은 이전 행을 바꾸지 않고 다음 버전으로 더한다.
 */
@Profile("!migrate")
@Service
public class ContractDocumentService {

  static final long MAX_BYTES = 20L * 1024 * 1024;
  static final Duration DOWNLOAD_TTL = Duration.ofMinutes(10);
  static final String MASKED_NOTICE = "번호를 가린 사본은 법령에 따른 원본 보존을 마친 것으로 보지 않습니다.";
  private static final int REASON_MAX = 2_000;
  private static final Set<String> KEYS =
      Set.of(
          "contractRevisionId",
          "documentType",
          "writtenDate",
          "deliveredDate",
          "datePendingReason",
          "piiReviewed",
          "piiMaskingConfirmed");
  private static final Map<String, String> CONTENT_TYPES =
      Map.of(
          "pdf", "application/pdf", "jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png");

  private final ContractRepository contracts;
  private final ContractHistoryRepository histories;
  private final ContractDocumentRepository documents;
  private final ContractSupport support;
  private final ObjectStorage storage;

  public ContractDocumentService(
      ContractRepository contracts,
      ContractHistoryRepository histories,
      ContractDocumentRepository documents,
      ContractSupport support,
      ObjectStorage storage) {
    this.contracts = contracts;
    this.histories = histories;
    this.documents = documents;
    this.support = support;
    this.storage = storage;
  }

  @Transactional
  public ContractDocumentResponse upload(
      StaffPrincipal principal, Long contractId, Map<String, String> params, MultipartFile file) {
    ContractRow row = support.lock(principal.officeId(), contractId);
    Upload upload = parse(params, file, null);
    String type = upload.documentType();
    requireLatestRevision(row, upload.contractRevisionId());
    if (documents.versionExists(contractId, type, 1)) {
      throw new BusinessException(ErrorCode.DOCUMENT_VERSION_CONFLICT);
    }
    return store(principal, row, upload, 1, null);
  }

  @Transactional
  public ContractDocumentResponse revise(
      StaffPrincipal principal,
      Long contractId,
      Long documentId,
      Map<String, String> params,
      MultipartFile file) {
    ContractRow row = support.lock(principal.officeId(), contractId);
    DocumentRow previous =
        documents
            .find(contractId, documentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    Upload upload = parse(params, file, previous.documentType());
    requireLatestRevision(row, upload.contractRevisionId());
    int versionNo = previous.versionNo() + 1;
    if (documents.versionExists(contractId, previous.documentType(), versionNo)) {
      throw new BusinessException(ErrorCode.DOCUMENT_VERSION_CONFLICT);
    }
    return store(principal, row, upload, versionNo, previous.id());
  }

  @Transactional(readOnly = true)
  public ContractDocumentResponse.DocumentDownload download(
      StaffPrincipal principal, Long contractId, Long documentId) {
    ContractRow row =
        contracts
            .find(principal.officeId(), contractId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    DocumentRow document =
        documents
            .find(contractId, documentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    Instant now = support.now();
    String url = storage.issueDownloadUrl(document.objectKey(), DOWNLOAD_TTL).toString();
    return new ContractDocumentResponse.DocumentDownload(
        document.id(),
        url,
        TimeRules.toDisplayOffset(now.plus(DOWNLOAD_TTL)),
        row.officeId(),
        row.version());
  }

  /** 초안이거나 제출한 계약 버전이 같은 계약의 최신 버전이 아니면 409 CONTRACT_REVISION_CONFLICT다. */
  private void requireLatestRevision(ContractRow row, long contractRevisionId) {
    Optional<RevisionRow> latest =
        row.isDraft() ? Optional.empty() : histories.latestRevision(row.id());
    if (latest.isEmpty() || latest.get().id() != contractRevisionId) {
      throw new BusinessException(ErrorCode.CONTRACT_REVISION_CONFLICT);
    }
  }

  private ContractDocumentResponse store(
      StaffPrincipal principal, ContractRow row, Upload upload, int versionNo, Long supersedes) {
    Instant now = support.now();
    int years = retentionYears(upload.documentType());
    LocalDate retentionEnd = retentionEnd(upload.writtenDate(), upload.deliveredDate(), years);
    String key =
        "contracts/" + row.id() + "/documents/" + UUID.randomUUID() + "." + upload.extension();
    storage.put(key, upload.content(), upload.contentType());
    Long id =
        documents.insert(
            new NewDocument(
                row.id(),
                upload.contractRevisionId(),
                upload.documentType(),
                versionNo,
                supersedes,
                upload.writtenDate(),
                upload.deliveredDate(),
                upload.datePendingReason(),
                retentionEnd,
                years,
                key,
                upload.piiMaskingConfirmed(),
                principal.membershipId()),
            now);
    contracts.bumpVersion(row.id(), now);
    ContractRow updated = contracts.find(principal.officeId(), row.id()).orElseThrow();
    return new ContractDocumentResponse(
        id,
        upload.documentType(),
        versionNo,
        upload.contractRevisionId(),
        upload.writtenDate(),
        upload.deliveredDate(),
        upload.datePendingReason(),
        years,
        retentionEnd,
        upload.piiMaskingConfirmed(),
        supersedes,
        upload.piiMaskingConfirmed() ? MASKED_NOTICE : null,
        updated.officeId(),
        updated.version());
  }

  static int retentionYears(String documentType) {
    return "SALES_CONTRACT".equals(documentType) ? 5 : 3;
  }

  /** 작성일·교부일이 모두 있으면 늦은 날의 연수 뒤 같은 날이다. 2월 29일의 대응일이 없으면 2월 말일이다. */
  static LocalDate retentionEnd(LocalDate written, LocalDate delivered, int years) {
    if (written == null || delivered == null) {
      return null;
    }
    LocalDate later = written.isAfter(delivered) ? written : delivered;
    return later.plusYears(years);
  }

  private record Upload(
      long contractRevisionId,
      String documentType,
      LocalDate writtenDate,
      LocalDate deliveredDate,
      String datePendingReason,
      boolean piiMaskingConfirmed,
      byte[] content,
      String extension,
      String contentType) {}

  /** 멀티파트 값을 읽는다. 정정본은 원래 서류 종류를 쓰고 다른 종류를 보내면 400이다. */
  private Upload parse(Map<String, String> params, MultipartFile file, String fixedType) {
    for (String name : params.keySet()) {
      if (!KEYS.contains(name)) {
        throw CustomerInput.invalid(name, FieldErrorCode.NOT_ALLOWED, "허용되지 않는 필드입니다.");
      }
    }
    long revisionId = requiredLong(params, "contractRevisionId");
    String type = blankToNull(params.get("documentType"));
    if (type == null && fixedType == null) {
      throw CustomerInput.invalid("documentType", FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    if (type != null
        && (!Set.of("SALES_CONTRACT", "DISCLOSURE_STATEMENT").contains(type)
            || (fixedType != null && !fixedType.equals(type)))) {
      throw CustomerInput.invalid("documentType", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 값입니다.");
    }
    LocalDate today = ContractSupport.today(support.now());
    LocalDate written = date(params, "writtenDate", today);
    LocalDate delivered = date(params, "deliveredDate", today);
    String reason = blankToNull(params.get("datePendingReason"));
    if (written == null || delivered == null) {
      if (reason == null) {
        throw CustomerInput.invalid(
            "datePendingReason", FieldErrorCode.REQUIRED, "작성일이나 교부일을 모르면 사유가 필요합니다.");
      }
      if (reason.length() > REASON_MAX) {
        throw CustomerInput.invalid(
            "datePendingReason", FieldErrorCode.OUT_OF_RANGE, "2000자 이하여야 합니다.");
      }
      if (SensitiveDataDetector.detect(reason)) {
        throw BusinessException.withFieldErrors(
            ErrorCode.SENSITIVE_DATA_DETECTED,
            List.of(
                new FieldErrorItem(
                    "datePendingReason", FieldErrorCode.INVALID_VALUE, "민감 번호가 감지됐습니다.")));
      }
    } else if (reason != null) {
      throw CustomerInput.invalid(
          "datePendingReason", FieldErrorCode.NOT_ALLOWED, "작성일과 교부일이 모두 있으면 사유를 비웁니다.");
    }
    Boolean reviewed = bool(params, "piiReviewed");
    if (!Boolean.TRUE.equals(reviewed)) {
      throw CustomerInput.invalid(
          "piiReviewed",
          reviewed == null ? FieldErrorCode.REQUIRED : FieldErrorCode.NOT_ALLOWED,
          "고유식별정보 포함 여부를 확인해야 합니다.");
    }
    boolean masked = Boolean.TRUE.equals(bool(params, "piiMaskingConfirmed"));
    if (file == null || file.isEmpty()) {
      throw CustomerInput.invalid("file", FieldErrorCode.REQUIRED, "파일이 필요합니다.");
    }
    if (file.getSize() > MAX_BYTES) {
      throw CustomerInput.invalid("file", FieldErrorCode.OUT_OF_RANGE, "파일은 20MB까지 올릴 수 있습니다.");
    }
    String extension = extension(file.getOriginalFilename());
    String contentType = CONTENT_TYPES.get(extension);
    byte[] content;
    try {
      content = file.getBytes();
    } catch (IOException e) {
      throw CustomerInput.invalid("file", FieldErrorCode.INVALID_VALUE, "파일을 읽을 수 없습니다.");
    }
    if (contentType == null || !signatureMatches(content, contentType)) {
      throw CustomerInput.invalid(
          "file", FieldErrorCode.NOT_ALLOWED, "파일 형식은 PDF, JPG, PNG만 올릴 수 있습니다.");
    }
    return new Upload(
        revisionId,
        type == null ? fixedType : type,
        written,
        delivered,
        reason,
        masked,
        content,
        extension.equals("jpeg") ? "jpg" : extension,
        contentType);
  }

  private static long requiredLong(Map<String, String> params, String key) {
    String value = blankToNull(params.get(key));
    if (value == null) {
      throw CustomerInput.invalid(key, FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw CustomerInput.invalid(key, FieldErrorCode.TYPE_MISMATCH, "정수여야 합니다.");
    }
  }

  private static LocalDate date(Map<String, String> params, String key, LocalDate today) {
    String value = blankToNull(params.get(key));
    if (value == null) {
      return null;
    }
    LocalDate date;
    try {
      date = LocalDate.parse(value);
    } catch (DateTimeParseException e) {
      throw CustomerInput.invalid(key, FieldErrorCode.INVALID_FORMAT, "yyyy-MM-dd 형식이어야 합니다.");
    }
    if (date.isAfter(today)) {
      throw CustomerInput.invalid(key, FieldErrorCode.OUT_OF_RANGE, "미래 날짜는 입력할 수 없습니다.");
    }
    return date;
  }

  private static Boolean bool(Map<String, String> params, String key) {
    String value = blankToNull(params.get(key));
    if (value == null) {
      return null;
    }
    return switch (value) {
      case "true" -> true;
      case "false" -> false;
      default ->
          throw CustomerInput.invalid(key, FieldErrorCode.TYPE_MISMATCH, "true 또는 false여야 합니다.");
    };
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static String extension(String filename) {
    if (filename == null) {
      return "";
    }
    int dot = filename.lastIndexOf('.');
    return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private static boolean signatureMatches(byte[] content, String contentType) {
    return switch (contentType) {
      case "application/pdf" -> startsWith(content, new byte[] {'%', 'P', 'D', 'F'});
      case "image/jpeg" -> startsWith(content, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
      case "image/png" ->
          startsWith(content, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
      default -> false;
    };
  }

  private static boolean startsWith(byte[] content, byte[] prefix) {
    if (content.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (content[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }
}
