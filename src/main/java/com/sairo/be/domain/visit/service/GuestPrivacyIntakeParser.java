package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.support.PhoneNumbers;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

public final class GuestPrivacyIntakeParser {

  public static final int NOTE_MAX = 1000;
  static final int TOKEN_MAX = 512;

  public enum LinkKind {
    APPROVER_PROPERTY,
    CUSTOMER
  }

  public enum RequestType {
    ACCESS,
    CORRECTION,
    DELETION,
    SUSPENSION
  }

  public enum RequesterRole {
    SELF,
    AGENT
  }

  public enum ContactChannel {
    EMAIL,
    PHONE
  }

  public record Intake(
      LinkKind linkKind,
      String token,
      RequestType requestType,
      RequesterRole requesterRole,
      ContactChannel contactChannel,
      String contact,
      String requestNote,
      String acknowledgedNoticeVersion) {}

  private static final Set<String> KEYS =
      Set.of(
          "linkKind",
          "token",
          "requestType",
          "requesterRole",
          "contactChannel",
          "contact",
          "requestNote",
          "acknowledgedNoticeVersion");
  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");
  private static final int EMAIL_MAX = 254;

  private GuestPrivacyIntakeParser() {}

  public static Intake parse(JsonNode body) {
    CustomerInput.requireObject(body, KEYS);
    LinkKind linkKind = CustomerInput.requiredEnum(body, "linkKind", LinkKind.class);
    String token = CustomerInput.requiredText(body, "token", TOKEN_MAX, false);
    RequestType requestType = CustomerInput.requiredEnum(body, "requestType", RequestType.class);
    RequesterRole role = CustomerInput.requiredEnum(body, "requesterRole", RequesterRole.class);
    ContactChannel channel =
        CustomerInput.requiredEnum(body, "contactChannel", ContactChannel.class);
    String contact = CustomerInput.requiredText(body, "contact", 100, false);
    String note = CustomerInput.optionalText(body, "requestNote", NOTE_MAX, true);
    String acknowledged = CustomerInput.requiredText(body, "acknowledgedNoticeVersion", 50, false);
    return new Intake(
        linkKind,
        token,
        requestType,
        role,
        channel,
        normalizeContact(channel, contact),
        note,
        acknowledged);
  }

  public static String mask(ContactChannel channel, String contact) {
    if (channel == ContactChannel.PHONE) {
      String[] parts = contact.split("-");
      return parts[0] + "-****-" + parts[2];
    }
    int at = contact.indexOf('@');
    String local = contact.substring(0, at);
    String visible = local.substring(0, Math.min(2, Math.max(1, local.length() - 1)));
    return visible + "***" + contact.substring(at);
  }

  private static String normalizeContact(ContactChannel channel, String contact) {
    if (channel == ContactChannel.PHONE) {
      return PhoneNumbers.normalize(contact)
          .orElseThrow(() -> new BusinessException(ErrorCode.GUEST_CONTACT_INVALID));
    }
    if (contact.length() > EMAIL_MAX || !EMAIL.matcher(contact).matches()) {
      throw new BusinessException(ErrorCode.GUEST_CONTACT_INVALID);
    }
    return contact;
  }
}
