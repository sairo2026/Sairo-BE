package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.entity.SubscriptionPlan;

public final class PriceCalculator {

  public static final int TEAM_FIRST_TIER_MAX = 4;

  private PriceCalculator() {}

  public static SubscriptionPlan planFor(int memberCount) {
    return memberCount <= 1 ? SubscriptionPlan.SOLO : SubscriptionPlan.TEAM;
  }

  public static long monthlyAmount(PriceTable table, int memberCount) {
    if (memberCount < 1) {
      throw new IllegalArgumentException("과금 인원은 1명 이상이어야 합니다.");
    }
    if (memberCount == 1) {
      return table.soloAmount();
    }
    if (memberCount <= TEAM_FIRST_TIER_MAX) {
      return table.soloAmount() + (memberCount - 1) * table.teamFirstExtra();
    }
    return table.soloAmount()
        + (TEAM_FIRST_TIER_MAX - 1) * table.teamFirstExtra()
        + (long) (memberCount - TEAM_FIRST_TIER_MAX) * table.teamAfterFourExtra();
  }

  public static long teamUpperTierBase(PriceTable table) {
    return table.soloAmount() + (TEAM_FIRST_TIER_MAX - 1) * table.teamFirstExtra();
  }

  public record PriceTable(long soloAmount, long teamFirstExtra, long teamAfterFourExtra) {}
}
