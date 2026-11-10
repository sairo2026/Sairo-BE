package com.sairo.be.domain.property.dto.response;

import java.util.List;

public record PropertyCategoryListResponse(List<Item> items) {

  public record Item(Long categoryId, String name, boolean isSystem, boolean active) {}
}
