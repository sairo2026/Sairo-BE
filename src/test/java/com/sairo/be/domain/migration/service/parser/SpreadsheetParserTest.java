package com.sairo.be.domain.migration.service.parser;

import static com.sairo.be.support.SpreadsheetFixtures.csv;
import static com.sairo.be.support.SpreadsheetFixtures.csvWithRows;
import static com.sairo.be.support.SpreadsheetFixtures.row;
import static com.sairo.be.support.SpreadsheetFixtures.sheets;
import static com.sairo.be.support.SpreadsheetFixtures.xls;
import static com.sairo.be.support.SpreadsheetFixtures.xlsx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.SpreadsheetFixtures;
import com.sairo.be.support.SpreadsheetFixtures.Formula;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpreadsheetParserTest {

  private static void assertRejected(Runnable action, ErrorCode expected) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
  }

  @Test
  void CSV는_첫_줄을_머리글로_읽고_행_번호는_파일의_줄_번호를_따른다() {
    ParsedFile file =
        SpreadsheetParser.parse(
            csv("이름,연락처", "홍길동,010-1111-2222", "", "김철수,010-3333-4444"), "a.csv", "Sheet1");

    ParsedSheet sheet = file.sheets().get(0);
    assertThat(sheet.name()).isEqualTo("Sheet1");
    assertThat(sheet.headers()).containsExactly("이름", "연락처");
    assertThat(sheet.rowCount()).isEqualTo(2);
    assertThat(sheet.columnCount()).isEqualTo(2);
    assertThat(sheet.rows()).extracting(ParsedRow::rowNumber).containsExactly(2, 4);
    assertThat(sheet.rows().get(1).cells()).containsExactly("김철수", "010-3333-4444");
  }

  @Test
  void CSV는_UTF8_BOM과_CP949를_모두_읽는다() {
    byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    byte[] body = csv("이름,메모", "홍길동,메모");
    byte[] withBom = new byte[bom.length + body.length];
    System.arraycopy(bom, 0, withBom, 0, bom.length);
    System.arraycopy(body, 0, withBom, bom.length, body.length);
    assertThat(SpreadsheetParser.parse(withBom, "a.csv", "Sheet1").sheets().get(0).headers())
        .containsExactly("이름", "메모");

    byte[] cp949 = csv(Charset.forName("x-windows-949"), "이름,메모", "홍길동,메모");
    ParsedSheet sheet = SpreadsheetParser.parse(cp949, "a.csv", "Sheet1").sheets().get(0);
    assertThat(sheet.headers()).containsExactly("이름", "메모");
    assertThat(sheet.rows().get(0).cells()).containsExactly("홍길동", "메모");
  }

  @Test
  void CSV는_탭과_세미콜론_구분자와_따옴표_안의_줄바꿈을_처리한다() {
    ParsedSheet tab =
        SpreadsheetParser.parse(csv("이름\t연락처", "홍길동\t010"), "a.csv", "Sheet1").sheets().get(0);
    assertThat(tab.headers()).containsExactly("이름", "연락처");

    ParsedSheet semicolon =
        SpreadsheetParser.parse(csv("이름;연락처", "홍길동;010"), "a.csv", "Sheet1").sheets().get(0);
    assertThat(semicolon.headers()).containsExactly("이름", "연락처");

    ParsedSheet quoted =
        SpreadsheetParser.parse(csv("이름,메모", "홍길동,\"한 줄\n두 줄, \"\"인용\"\"\""), "a.csv", "Sheet1")
            .sheets()
            .get(0);
    assertThat(quoted.rows().get(0).cell(1)).isEqualTo("한 줄\n두 줄, \"인용\"");
  }

  @Test
  void 머리글이_비었거나_겹치면_고유한_이름으로_바꾼다() {
    ParsedSheet sheet =
        SpreadsheetParser.parse(csv("이름,,이름", "a,b,c"), "a.csv", "Sheet1").sheets().get(0);

    assertThat(sheet.headers()).containsExactly("이름", "열2", "이름 (2)");
  }

  @Test
  void XLSX는_시트별로_읽고_숫자_날짜_불리언을_저장된_값으로_돌려준다() {
    byte[] content =
        xlsx(
            sheets(
                "고객",
                List.of(
                    row("번호", "이름", "가입일", "법인", "면적"),
                    row(1200, "홍길동", LocalDate.of(2026, 1, 5), true, 3.5)),
                "빈시트",
                List.of(),
                "두번째",
                List.of(row("a"), row("b"))));

    ParsedFile file = SpreadsheetParser.parse(content, "a.xlsx", "고객");

    assertThat(file.sheets()).extracting(ParsedSheet::name).containsExactly("고객", "두번째");
    ParsedSheet sheet = file.find("고객").orElseThrow();
    assertThat(sheet.rows().get(0).rowNumber()).isEqualTo(2);
    assertThat(sheet.rows().get(0).cells())
        .containsExactly("1200", "홍길동", "2026-01-05", "TRUE", "3.5");
    assertThat(file.find("두번째").orElseThrow().rows()).isEmpty();
    assertThat(file.find("두번째").orElseThrow().rowCount()).isEqualTo(1);
  }

  @Test
  void XLS도_같은_규칙으로_읽는다() {
    byte[] content =
        xls(
            sheets(
                "Sheet1",
                List.of(
                    row("번호", "이름", "가입일", "법인"), row(7, "홍길동", LocalDate.of(2026, 1, 5), false))));

    ParsedSheet sheet = SpreadsheetParser.parse(content, "a.xls", "Sheet1").sheets().get(0);

    assertThat(sheet.headers()).containsExactly("번호", "이름", "가입일", "법인");
    assertThat(sheet.rows().get(0).cells()).containsExactly("7", "홍길동", "2026-01-05", "false");
  }

  @Test
  void 수식은_실행하지_않고_수식_문자열도_값으로_읽지_않는다() {
    byte[] xlsxContent = xlsx(sheets("S", List.of(row("값"), row(new Formula("1+1")), row("=1+1"))));
    ParsedSheet sheet = SpreadsheetParser.parse(xlsxContent, "a.xlsx", "S").sheets().get(0);

    assertThat(sheet.rows()).extracting(r -> r.cell(0)).doesNotContain("2", "1+1");

    byte[] xlsContent = xls(sheets("S", List.of(row("값"), row(new Formula("1+1")))));
    ParsedSheet legacy = SpreadsheetParser.parse(xlsContent, "a.xls", "S").sheets().get(0);
    assertThat(legacy.rows()).extracting(r -> r.cell(0)).doesNotContain("1+1");
  }

  @Test
  void 데이터가_1만_행이면_읽고_1만_1행이면_거절한다() {
    assertThat(
            SpreadsheetParser.parse(csvWithRows(10_000, 2), "a.csv", null)
                .sheets()
                .get(0)
                .rowCount())
        .isEqualTo(10_000);

    assertRejected(
        () -> SpreadsheetParser.parse(csvWithRows(10_001, 2), "a.csv", null),
        ErrorCode.IMPORT_LIMIT_EXCEEDED);
  }

  @Test
  void 열이_100개이면_읽고_101개이면_거절한다() {
    assertThat(
            SpreadsheetParser.parse(csvWithRows(2, 100), "a.csv", null)
                .sheets()
                .get(0)
                .columnCount())
        .isEqualTo(100);

    assertRejected(
        () -> SpreadsheetParser.parse(csvWithRows(2, 101), "a.csv", null),
        ErrorCode.IMPORT_LIMIT_EXCEEDED);
  }

  @Test
  void 엑셀도_시트당_행과_열_한도를_검사한다() {
    List<List<Object>> tooMany = new java.util.ArrayList<>();
    tooMany.add(row("a"));
    for (int i = 0; i < 10_001; i++) {
      tooMany.add(row(i));
    }
    assertRejected(
        () -> SpreadsheetParser.parse(xlsx(sheets("S", tooMany)), "a.xlsx", null),
        ErrorCode.IMPORT_LIMIT_EXCEEDED);

    Object[] wide = new Object[101];
    java.util.Arrays.fill(wide, "x");
    assertRejected(
        () -> SpreadsheetParser.parse(xlsx(sheets("S", List.of(row(wide)))), "a.xlsx", null),
        ErrorCode.IMPORT_LIMIT_EXCEEDED);
  }

  @Test
  void 암호화된_엑셀은_거절한다() {
    byte[] encrypted = SpreadsheetFixtures.encryptedOle2();

    assertRejected(
        () -> SpreadsheetParser.parse(encrypted, "a.xlsx", null),
        ErrorCode.ENCRYPTED_FILE_REJECTED);
    assertRejected(
        () -> SpreadsheetParser.parse(encrypted, "a.xls", null), ErrorCode.ENCRYPTED_FILE_REJECTED);
  }

  @Test
  void 확장자와_파일_내용이_다르거나_지원하지_않는_형식이면_거절한다() {
    byte[] xlsxContent = xlsx(sheets("S", List.of(row("a"), row("b"))));
    byte[] xlsContent = xls(sheets("S", List.of(row("a"), row("b"))));

    assertRejected(
        () -> SpreadsheetParser.parse(csv("a,b"), "a.xlsx", null), ErrorCode.UNSUPPORTED_FILE_TYPE);
    assertRejected(
        () -> SpreadsheetParser.parse(xlsxContent, "a.xls", null), ErrorCode.UNSUPPORTED_FILE_TYPE);
    assertRejected(
        () -> SpreadsheetParser.parse(xlsContent, "a.xlsx", null), ErrorCode.UNSUPPORTED_FILE_TYPE);
    assertRejected(
        () -> SpreadsheetParser.parse(xlsxContent, "a.csv", null), ErrorCode.UNSUPPORTED_FILE_TYPE);
    assertRejected(
        () -> SpreadsheetParser.parse(csv("a,b"), "a.txt", null), ErrorCode.UNSUPPORTED_FILE_TYPE);
    assertRejected(
        () -> SpreadsheetParser.parse(csv("a,b"), "noextension", null),
        ErrorCode.UNSUPPORTED_FILE_TYPE);
    assertRejected(
        () -> SpreadsheetParser.parse(new byte[] {'a', 0, 'b'}, "a.csv", null),
        ErrorCode.UNSUPPORTED_FILE_TYPE);
  }

  @Test
  void 깨진_엑셀_파일은_형식_오류로_거절한다() {
    byte[] broken = "PK\u0003\u0004깨진 파일".getBytes(StandardCharsets.UTF_8);

    assertRejected(
        () -> SpreadsheetParser.parse(broken, "a.xlsx", null), ErrorCode.UNSUPPORTED_FILE_TYPE);
  }

  @Test
  void 읽을_수_있는_데이터가_없으면_INVALID_REQUEST다() {
    assertRejected(
        () -> SpreadsheetParser.parse(csv("", ""), "a.csv", null), ErrorCode.INVALID_REQUEST);
  }
}
