package com.sairo.be.domain.coordination.controller;

import com.sairo.be.domain.coordination.dto.request.AvailableTimesSubmitRequest;
import com.sairo.be.domain.coordination.dto.response.PublicVisitResponse;
import com.sairo.be.domain.coordination.service.PublicVisitResponseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/public/visit-responses/{token}")
@RequiredArgsConstructor
public class PublicVisitResponseController {

  private final PublicVisitResponseService publicVisitResponseService;

  @GetMapping
  public PublicVisitResponse get(@PathVariable String token) {
    return publicVisitResponseService.getByToken(token);
  }

  @PostMapping("/available-times")
  public PublicVisitResponse submitAvailableTimes(
      @PathVariable String token, @Valid @RequestBody AvailableTimesSubmitRequest request) {
    return publicVisitResponseService.submitAvailableTimes(token, request);
  }

  @PostMapping("/no-availability")
  public PublicVisitResponse submitNoAvailability(@PathVariable String token) {
    return publicVisitResponseService.submitNoAvailability(token);
  }
}
