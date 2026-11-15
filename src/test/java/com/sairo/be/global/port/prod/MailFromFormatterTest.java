package com.sairo.be.global.port.prod;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MailFromFormatterTest {
  private static final String ADDRESS = "noreply@sairo.agency";

  @Test
  void wrapsDisplayNameInQuotes() {
    assertEquals(
        "\"사이로(SAIRO)\" <noreply@sairo.agency>", MailFromFormatter.display(ADDRESS, "사이로(SAIRO)"));
  }

  @Test
  void escapesQuotesAndBackslashes() {
    assertEquals(
        "\"A \\\"B\\\" C\\\\D\" <noreply@sairo.agency>",
        MailFromFormatter.display(ADDRESS, "A \"B\" C\\D"));
  }

  @Test
  void keepsAddressThatAlreadyHasDisplayName() {
    assertEquals(
        "Other <other@sairo.agency>",
        MailFromFormatter.display("Other <other@sairo.agency>", "사이로(SAIRO)"));
  }

  @Test
  void usesAddressOnlyWhenNameIsBlank() {
    assertEquals(ADDRESS, MailFromFormatter.display(ADDRESS, ""));
    assertEquals(ADDRESS, MailFromFormatter.display(ADDRESS, "  "));
    assertEquals(ADDRESS, MailFromFormatter.display(ADDRESS, null));
  }

  @Test
  void replacesControlCharactersToBlockHeaderInjection() {
    assertEquals(
        "\"A  Bcc: x@y.z\" <noreply@sairo.agency>",
        MailFromFormatter.display(ADDRESS, "A\r\nBcc: x@y.z"));
  }

  @Test
  void asciiDisplayDropsNonAsciiNames() {
    assertEquals(ADDRESS, MailFromFormatter.asciiDisplay(ADDRESS, "사이로(SAIRO)"));
    assertEquals(
        "\"SAIRO\" <noreply@sairo.agency>", MailFromFormatter.asciiDisplay(ADDRESS, "SAIRO"));
  }
}
