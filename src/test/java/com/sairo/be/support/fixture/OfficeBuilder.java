package com.sairo.be.support.fixture;

import com.sairo.be.domain.office.entity.Office;
import com.sairo.be.domain.office.entity.OfficeEntityType;
import java.util.concurrent.atomic.AtomicLong;

public final class OfficeBuilder {

  private static final AtomicLong SEQUENCE = new AtomicLong(0L);

  private final long sequence = SEQUENCE.incrementAndGet();
  private String name = "테스트공인중개사사무소";
  private OfficeEntityType entityType = OfficeEntityType.INDIVIDUAL;
  private String registrationAuthority = "서울특별시 종로구";
  private String licenseNumber = String.format("11110-2026-%05d", sequence);
  private String businessNumber = String.format("%010d", 1_000_000_000L + sequence);
  private String representativeName = "홍대표";
  private String representativePhone = "010-0000-0000";
  private String address = "서울특별시 종로구 테스트로 1";

  private OfficeBuilder() {}

  public static OfficeBuilder anOffice() {
    return new OfficeBuilder();
  }

  public OfficeBuilder name(String name) {
    this.name = name;
    return this;
  }

  public OfficeBuilder entityType(OfficeEntityType entityType) {
    this.entityType = entityType;
    return this;
  }

  public OfficeBuilder representativeName(String representativeName) {
    this.representativeName = representativeName;
    return this;
  }

  public Office build() {
    return Office.register(
        name,
        entityType,
        registrationAuthority,
        licenseNumber,
        businessNumber,
        representativeName,
        representativePhone,
        address);
  }
}
