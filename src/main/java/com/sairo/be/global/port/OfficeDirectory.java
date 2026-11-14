package com.sairo.be.global.port;

import java.util.Optional;

public interface OfficeDirectory {

  record OfficeSummary(Long officeId, String name, String contactPhone) {}

  Optional<OfficeSummary> find(Long officeId);
}
