package com.sairo.be.global.support;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PlainTextValidator implements ConstraintValidator<PlainText, CharSequence> {

  private int min;
  private int max;

  @Override
  public void initialize(PlainText annotation) {
    this.min = annotation.min();
    this.max = annotation.max();
  }

  @Override
  public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
    if (value == null) {
      return min == 0;
    }
    return InputRules.isAcceptedText(value, min, max);
  }
}
