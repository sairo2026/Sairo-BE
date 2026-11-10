package com.sairo.be.global.error;

import com.sairo.be.global.support.InputRules;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.validation.FieldError;

final class FieldErrorMapper {

  private FieldErrorMapper() {}

  static FieldErrorItem fromFieldError(FieldError error) {
    return new FieldErrorItem(
        error.getField(),
        codeOf(error, error.getRejectedValue()),
        defaultMessage(error.getDefaultMessage()));
  }

  static FieldErrorItem fromResolvable(String field, MessageSourceResolvable error, Object value) {
    return new FieldErrorItem(
        field, codeOf(error, value), defaultMessage(error.getDefaultMessage()));
  }

  static FieldErrorCode codeOf(MessageSourceResolvable error, Object rejectedValue) {
    String[] codes = error.getCodes();
    String constraint = codes == null || codes.length == 0 ? "" : codes[codes.length - 1];
    return switch (constraint) {
      case "NotNull", "NotBlank", "NotEmpty" -> FieldErrorCode.REQUIRED;
      case "Size", "Length" -> sizeCode(error, rejectedValue);
      case "Min",
          "Max",
          "DecimalMin",
          "DecimalMax",
          "Positive",
          "PositiveOrZero",
          "Negative",
          "NegativeOrZero",
          "Range" ->
          FieldErrorCode.OUT_OF_RANGE;
      case "Pattern", "Email" -> FieldErrorCode.INVALID_FORMAT;
      case "PlainText" -> plainTextCode(error, rejectedValue);
      case "typeMismatch" -> FieldErrorCode.TYPE_MISMATCH;
      default -> FieldErrorCode.INVALID_VALUE;
    };
  }

  private static FieldErrorCode sizeCode(MessageSourceResolvable error, Object rejectedValue) {
    Object[] arguments = error.getArguments();
    if (arguments != null
        && arguments.length >= 3
        && arguments[2] instanceof Integer min
        && rejectedValue instanceof CharSequence text
        && text.length() < min) {
      return FieldErrorCode.TOO_SHORT;
    }
    return FieldErrorCode.TOO_LONG;
  }

  private static FieldErrorCode plainTextCode(MessageSourceResolvable error, Object rejectedValue) {
    if (!(rejectedValue instanceof CharSequence text)) {
      return FieldErrorCode.REQUIRED;
    }
    if (InputRules.containsHtml(text)) {
      return FieldErrorCode.HTML_NOT_ALLOWED;
    }
    int length = InputRules.length(text);
    if (length == 0) {
      return FieldErrorCode.REQUIRED;
    }
    Object[] arguments = error.getArguments();
    boolean belowMinimum =
        arguments != null
            && arguments.length >= 3
            && arguments[2] instanceof Integer min
            && length < min;
    return belowMinimum ? FieldErrorCode.TOO_SHORT : FieldErrorCode.TOO_LONG;
  }

  private static String defaultMessage(String message) {
    return message == null ? "올바르지 않은 값입니다." : message;
  }
}
