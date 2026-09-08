package com.sairo.be.domain.office.service;

import com.sairo.be.domain.office.entity.Office;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OfficeQueryService {

  private final OfficeRepository officeRepository;

  public String getOfficeName(Long officeId) {
    return officeRepository
        .findById(officeId)
        .map(Office::getName)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }
}
