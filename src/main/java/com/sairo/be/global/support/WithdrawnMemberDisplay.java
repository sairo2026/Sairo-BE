package com.sairo.be.global.support;

public final class WithdrawnMemberDisplay {

  public static final String PREFIX = "탈퇴한 구성원";

  private WithdrawnMemberDisplay() {}

  public static String name(boolean withdrawn, String storedName, Integer aliasNo) {
    if (!withdrawn) {
      return storedName;
    }
    return aliasNo == null ? PREFIX : PREFIX + " " + aliasNo;
  }

  public static String nameWithRole(
      boolean withdrawn, String storedName, Integer aliasNo, String roleLabel) {
    String display = name(withdrawn, storedName, aliasNo);
    if (!withdrawn || roleLabel == null || roleLabel.isBlank()) {
      return display;
    }
    return display + "(" + roleLabel + ")";
  }
}
