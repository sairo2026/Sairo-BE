package com.sairo.be.domain.auth.dto.response;

public record ChangedTermsItem(
    String termsCode,
    String submittedVersion,
    String currentVersion,
    String changeSummary,
    String documentUrl) {}
