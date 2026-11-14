package com.sairo.be.domain.support.service;

import com.sairo.be.domain.support.entity.ScanStatus;
import com.sairo.be.domain.support.entity.SupportInquiryAttachment;
import com.sairo.be.domain.support.repository.SupportInquiryAttachmentRepository;
import com.sairo.be.global.port.AttachmentScanner;
import com.sairo.be.global.port.AttachmentScanner.Verdict;
import com.sairo.be.global.port.ObjectStorage;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Profile("!migrate")
@Service
public class AttachmentScanService {

  static final Duration RECOVERY_DELAY = Duration.ofSeconds(30);
  static final int RECOVERY_BATCH = 50;

  private final SupportInquiryAttachmentRepository attachmentRepository;
  private final ObjectStorage objectStorage;
  private final AttachmentScanner scanner;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public AttachmentScanService(
      SupportInquiryAttachmentRepository attachmentRepository,
      ObjectStorage objectStorage,
      AttachmentScanner scanner,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.attachmentRepository = attachmentRepository;
    this.objectStorage = objectStorage;
    this.scanner = scanner;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  public ScanStatus scan(long attachmentId) {
    Optional<SupportInquiryAttachment> found = attachmentRepository.findById(attachmentId);
    if (found.isEmpty() || found.get().getScanStatus() != ScanStatus.PENDING) {
      return found.map(SupportInquiryAttachment::getScanStatus).orElse(null);
    }
    SupportInquiryAttachment attachment = found.get();
    Verdict verdict;
    try {
      verdict = judge(attachment);
    } catch (RuntimeException e) {
      log.warn("첨부 검사기를 사용할 수 없어 검사 중 상태로 둔다. attachmentId={}", attachmentId);
      return ScanStatus.PENDING;
    }
    return transactions.execute(status -> complete(attachmentId, verdict));
  }

  public int scanPending() {
    List<Long> ids =
        attachmentRepository.findPendingIdsCreatedBefore(
            clock.instant().minus(RECOVERY_DELAY), PageRequest.ofSize(RECOVERY_BATCH));
    ids.forEach(this::scan);
    return ids.size();
  }

  private Verdict judge(SupportInquiryAttachment attachment) {
    Optional<byte[]> content = objectStorage.find(attachment.getObjectKey());
    if (content.isEmpty()) {
      return Verdict.REJECTED;
    }
    return scanner.scan(attachment.getFileName(), attachment.getMediaType(), content.get());
  }

  private ScanStatus complete(long attachmentId, Verdict verdict) {
    SupportInquiryAttachment locked =
        attachmentRepository.findForUpdateById(attachmentId).orElse(null);
    if (locked == null || locked.getScanStatus() != ScanStatus.PENDING) {
      return locked == null ? null : locked.getScanStatus();
    }
    if (verdict == Verdict.CLEAN) {
      locked.markClean();
    } else {
      locked.markRejected();
    }
    attachmentRepository.saveAndFlush(locked);
    return locked.getScanStatus();
  }
}
