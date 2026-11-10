package com.sairo.be.domain.legal.service;

import java.util.List;

public record RetainedItemView(
    String itemCode,
    String itemName,
    String reason,
    List<String> reviewTargetLaws,
    int retentionDays,
    String anchorEvent,
    String ruleStatus) {}
