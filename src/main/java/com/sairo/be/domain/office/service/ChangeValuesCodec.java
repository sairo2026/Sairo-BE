package com.sairo.be.domain.office.service;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Profile("!migrate")
@Component
public class ChangeValuesCodec {

  private static final TypeReference<LinkedHashMap<String, String>> TYPE = new TypeReference<>() {};

  private final ObjectMapper objectMapper;

  public ChangeValuesCodec(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String write(Map<String, String> values) {
    return objectMapper.writeValueAsString(values);
  }

  public Map<String, String> read(String json) {
    return objectMapper.readValue(json, TYPE);
  }
}
