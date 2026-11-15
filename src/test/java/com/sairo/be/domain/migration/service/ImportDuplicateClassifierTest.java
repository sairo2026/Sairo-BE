package com.sairo.be.domain.migration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sairo.be.domain.customer.service.CustomerImportService;
import com.sairo.be.domain.migration.entity.ImportSkipReason;
import com.sairo.be.domain.migration.entity.ImportTargetType;
import com.sairo.be.domain.migration.repository.ImportRecordLinkRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImportDuplicateClassifierTest {

  private final ImportRecordLinkRepository linkRepository = mock(ImportRecordLinkRepository.class);
  private final CustomerImportService customerImportService = mock(CustomerImportService.class);
  private final ImportDuplicateClassifier classifier =
      new ImportDuplicateClassifier(linkRepository, customerImportService);

  private static NormalizedRow customer(String sourceId, String name, String phone) {
    Map<String, Object> values = new HashMap<>();
    values.put(ImportTargetFields.CUSTOMER_NAME, name);
    values.put(ImportTargetFields.CUSTOMER_PHONE, phone);
    return new NormalizedRow(values, List.of(), List.of(), sourceId);
  }

  @Test
  void 기존_고객과_원본_ID_조회는_파일_크기와_상관없이_500건_묶음으로_한다() {
    List<NormalizedRow> rows = new ArrayList<>();
    for (int i = 0; i < 1200; i++) {
      rows.add(customer("C-" + i, "고객" + i, "010-0000-" + String.format("%04d", i)));
    }
    when(linkRepository.findByOfficeIdAndSourceSystemAndSourceTypeAndSourceIdIn(
            anyLong(), any(), any(), anyCollection()))
        .thenReturn(List.of());
    when(customerImportService.findExistingMatches(anyLong(), anyCollection()))
        .thenReturn(List.of());

    classifier.open(1L, ImportTargetType.CUSTOMER, "양셀", rows);

    verify(customerImportService, times(3)).findExistingMatches(anyLong(), anyCollection());
    verify(linkRepository, times(3))
        .findByOfficeIdAndSourceSystemAndSourceTypeAndSourceIdIn(
            anyLong(), any(), any(), anyCollection());
  }

  @Test
  void 이름이나_전화번호가_없는_행은_기존_고객_조회_대상이_아니다() {
    List<NormalizedRow> rows =
        List.of(customer("C-1", "홍길동", null), customer("C-2", "", "010-1234-5678"));
    when(linkRepository.findByOfficeIdAndSourceSystemAndSourceTypeAndSourceIdIn(
            anyLong(), any(), any(), anyCollection()))
        .thenReturn(List.of());

    ImportDuplicateClassifier.Session session =
        classifier.open(1L, ImportTargetType.CUSTOMER, "양셀", rows);

    verify(customerImportService, times(0)).findExistingMatches(anyLong(), anyCollection());
    assertThat(session.judge(rows.get(0)).skipped()).isFalse();
    assertThat(session.judge(rows.get(1)).skipped()).isFalse();
  }

  @Test
  void 같은_이름_전화번호의_두_번째_행은_첫_행을_만든_경우에만_파일_안_중복이다() {
    NormalizedRow first = customer("C-1", "홍 길동", "010-1234-5678");
    NormalizedRow second = customer("C-2", "홍길동", "01012345678");
    when(linkRepository.findByOfficeIdAndSourceSystemAndSourceTypeAndSourceIdIn(
            anyLong(), any(), any(), anyCollection()))
        .thenReturn(List.of());
    when(customerImportService.findExistingMatches(anyLong(), anyCollection()))
        .thenReturn(List.of());

    ImportDuplicateClassifier.Session session =
        classifier.open(1L, ImportTargetType.CUSTOMER, "양셀", List.of(first, second));

    assertThat(session.judge(first).skipped()).isFalse();
    assertThat(session.judge(second).skipped()).isFalse();
    session.markCreated(first);
    ImportDuplicateClassifier.Judgement judgement = session.judge(second);
    assertThat(judgement.reason()).isEqualTo(ImportSkipReason.DUPLICATE_IN_FILE);
  }

  @Test
  void 매물은_원본_ID만_보고_이름_전화번호_규칙을_적용하지_않는다() {
    NormalizedRow row = customer("P-1", "홍길동", "010-1234-5678");
    when(linkRepository.findByOfficeIdAndSourceSystemAndSourceTypeAndSourceIdIn(
            anyLong(), any(), any(), anyCollection()))
        .thenReturn(List.of());

    ImportDuplicateClassifier.Session session =
        classifier.open(1L, ImportTargetType.PROPERTY, "양셀", List.of(row, row));

    verify(customerImportService, times(0)).findExistingMatches(anyLong(), anyCollection());
    session.markCreated(row);
    assertThat(session.judge(customer("P-2", "홍길동", "010-1234-5678")).skipped()).isFalse();
    assertThat(session.judge(row).reason()).isEqualTo(ImportSkipReason.DUPLICATE_IN_FILE);
  }
}
