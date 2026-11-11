package com.sairo.be.domain.support.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SupportInquiryTest {

  private static final Instant NOW = Instant.parse("2026-11-10T01:00:00Z");

  private static SupportInquiry inquiry() {
    return SupportInquiry.open(
        1L, null, InquiryCategory.BILLING, "문의", null, NOW.plusSeconds(86_400), NOW);
  }

  @Test
  void 접수하면_OPEN과_NORMAL로_시작한다() {
    SupportInquiry inquiry = inquiry();

    assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.OPEN);
    assertThat(inquiry.getPriority()).isEqualTo(InquiryPriority.NORMAL);
    assertThat(inquiry.getAssignedOperatorId()).isNull();
  }

  @Test
  void 배정하면_OPEN은_ASSIGNED가_되고_완료_티켓은_배정할_수_없다() {
    SupportInquiry inquiry = inquiry();

    inquiry.assignTo(7L, NOW);

    assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.ASSIGNED);
    assertThat(inquiry.getAssignedOperatorId()).isEqualTo(7L);
    inquiry.answered("끝", false, true, NOW);
    assertThatThrownBy(() -> inquiry.assignTo(8L, NOW))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_TRANSITION));
  }

  @Test
  void 운영자_답변은_보완_대기와_완료와_답변됨으로_전이한다() {
    SupportInquiry waiting = inquiry();
    waiting.answered("확인 부탁드립니다", true, false, NOW);
    SupportInquiry closed = inquiry();
    closed.answered("해결했습니다", false, true, NOW);
    SupportInquiry answered = inquiry();
    answered.answered("안내드립니다", false, false, NOW);

    assertThat(waiting.getStatus()).isEqualTo(InquiryStatus.WAITING_FOR_CUSTOMER);
    assertThat(closed.getStatus()).isEqualTo(InquiryStatus.CLOSED);
    assertThat(closed.getClosedAt()).isNotNull();
    assertThat(answered.getStatus()).isEqualTo(InquiryStatus.ANSWERED);
    assertThat(answered.getAnswer()).isEqualTo("안내드립니다");
    assertThat(answered.getAnsweredAt()).isNotNull();
  }

  @Test
  void 보완_대기와_완료를_동시에_요청하거나_완료_티켓에_답변하면_거절한다() {
    SupportInquiry inquiry = inquiry();

    assertThatThrownBy(() -> inquiry.answered("a", true, true, NOW))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    inquiry.answered("끝", false, true, NOW);
    assertThatThrownBy(() -> inquiry.answered("b", false, false, NOW))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_TRANSITION));
  }

  @Test
  void 작성자가_추가하면_완료는_OPEN으로_재개하고_종료_시각을_지운다() {
    SupportInquiry inquiry = inquiry();
    inquiry.assignTo(7L, NOW);
    inquiry.answered("끝", false, true, NOW);

    inquiry.requesterAdded(NOW.plusSeconds(60));

    assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.OPEN);
    assertThat(inquiry.getClosedAt()).isNull();
  }

  @Test
  void 작성자가_추가하면_보완_대기와_답변됨은_담당자_유무에_따라_ASSIGNED_또는_OPEN이다() {
    SupportInquiry assigned = inquiry();
    assigned.assignTo(7L, NOW);
    assigned.answered("확인 부탁", true, false, NOW);
    SupportInquiry unassigned = inquiry();
    unassigned.answered("안내", false, false, NOW);

    assigned.requesterAdded(NOW);
    unassigned.requesterAdded(NOW);

    assertThat(assigned.getStatus()).isEqualTo(InquiryStatus.ASSIGNED);
    assertThat(unassigned.getStatus()).isEqualTo(InquiryStatus.OPEN);
  }

  @Test
  void 첨부_검사_상태는_검사_중에서만_통과나_거부로_바뀐다() {
    SupportInquiryAttachment clean =
        SupportInquiryAttachment.uploadedByRequester(
            1L, null, 1L, "key", "a.pdf", "application/pdf", 10, NOW);
    SupportInquiryAttachment rejected =
        SupportInquiryAttachment.uploadedByRequester(
            1L, null, 1L, "key", "b.pdf", "application/pdf", 10, NOW);

    assertThat(clean.getScanStatus()).isEqualTo(ScanStatus.PENDING);
    clean.markClean();
    rejected.markRejected();

    assertThat(clean.getScanStatus()).isEqualTo(ScanStatus.CLEAN);
    assertThat(rejected.getScanStatus()).isEqualTo(ScanStatus.REJECTED);
    assertThatThrownBy(clean::markRejected).isInstanceOf(BusinessException.class);
    assertThatThrownBy(rejected::markClean).isInstanceOf(BusinessException.class);
  }

  @Test
  void 내부_메모는_운영자_작성_INTERNAL_메시지로만_만든다() {
    SupportInquiryMessage note = SupportInquiryMessage.internalNote(1L, 7L, "메모", NOW);
    SupportInquiryMessage reply = SupportInquiryMessage.requesterReply(1L, 3L, "답글", NOW);

    assertThat(note.getVisibility()).isEqualTo(MessageVisibility.INTERNAL);
    assertThat(note.getAuthorType()).isEqualTo(AuthorType.OPERATOR);
    assertThat(note.getAuthorUserId()).isNull();
    assertThat(reply.getVisibility()).isEqualTo(MessageVisibility.PUBLIC);
    assertThat(reply.getAuthorOperatorId()).isNull();
  }
}
