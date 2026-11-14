package com.sairo.be.domain.support.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sairo.be.domain.visit.service.GuestPrivacyIntakeParser;
import com.sairo.be.domain.visit.service.GuestPrivacyIntakeParser.ContactChannel;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.support.TimeRules;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class SupportRulesTest {

  private static Instant kst(int day, int hour) {
    return ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, TimeRules.DISPLAY_ZONE).toInstant();
  }

  @Test
  void 첫_답변_목표는_접수_다음_영업일_같은_시각이다() {
    Instant monday = kst(9, 10);
    Instant thursday = kst(12, 15);

    assertThat(TimeRules.toDisplay(ResponseDueCalculator.firstResponseDue(monday)).getDayOfMonth())
        .isEqualTo(10);
    assertThat(TimeRules.toDisplay(ResponseDueCalculator.firstResponseDue(thursday)).getHour())
        .isEqualTo(15);
  }

  @Test
  void 금요일_토요일_일요일_접수는_다음_월요일이_목표다() {
    for (int day : new int[] {13, 14, 15}) {
      ZonedDateTime due = TimeRules.toDisplay(ResponseDueCalculator.firstResponseDue(kst(day, 11)));

      assertThat(due.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
      assertThat(due.getDayOfMonth()).isEqualTo(16);
    }
  }

  @Test
  void 확장자와_선언_형식이_맞아야_PDF_JPG_PNG만_허용한다() {
    assertThat(AttachmentRules.mediaTypeFor("a.PDF", "application/pdf"))
        .isEqualTo("application/pdf");
    assertThat(AttachmentRules.mediaTypeFor("a.jpeg", "image/jpg")).isEqualTo("image/jpeg");
    assertThat(AttachmentRules.mediaTypeFor("a.png", null)).isEqualTo("image/png");
    assertThat(AttachmentRules.mediaTypeFor("a.jpg", "image/jpeg; charset=binary"))
        .isEqualTo("image/jpeg");
    for (String name : new String[] {"a.gif", "a.pdf.exe", "a", ".pdf.", "a.html"}) {
      assertThatThrownBy(() -> AttachmentRules.mediaTypeFor(name, null))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED));
    }
    assertThatThrownBy(() -> AttachmentRules.mediaTypeFor("a.pdf", "text/html"))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void 파일_앞부분_시그니처가_형식과_다르면_거절한다() {
    AttachmentRules.requireSignatureMatches(
        "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.UTF_8));
    AttachmentRules.requireSignatureMatches(
        "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
    AttachmentRules.requireSignatureMatches(
        "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 1});

    assertThatThrownBy(
            () -> AttachmentRules.requireSignatureMatches("application/pdf", new byte[] {'%'}))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () ->
                AttachmentRules.requireSignatureMatches(
                    "image/png", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void 파일명은_경로를_떼고_255자로_줄인다() {
    assertThat(AttachmentRules.fileName("C:\\Users\\a\\증빙.pdf")).isEqualTo("증빙.pdf");
    assertThat(AttachmentRules.fileName("../../etc/passwd.pdf")).isEqualTo("passwd.pdf");
    assertThat(AttachmentRules.fileName(null)).isEqualTo("attachment");
    String longName = "가".repeat(300) + ".pdf";
    assertThat(AttachmentRules.fileName(longName)).hasSize(255).endsWith(".pdf");
  }

  @Test
  void 비회원_연락처는_일부만_보이게_가린다() {
    assertThat(GuestPrivacyIntakeParser.mask(ContactChannel.EMAIL, "requester@example.com"))
        .isEqualTo("re***@example.com");
    assertThat(GuestPrivacyIntakeParser.mask(ContactChannel.EMAIL, "ab@example.com"))
        .isEqualTo("a***@example.com");
    assertThat(GuestPrivacyIntakeParser.mask(ContactChannel.EMAIL, "a@example.com"))
        .isEqualTo("a***@example.com");
    assertThat(GuestPrivacyIntakeParser.mask(ContactChannel.PHONE, "010-1234-5678"))
        .isEqualTo("010-****-5678");
  }
}
