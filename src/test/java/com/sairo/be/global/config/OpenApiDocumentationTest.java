package com.sairo.be.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sairo.be.TestcontainersConfiguration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocumentationTest {

  private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");

  @Autowired private MockMvc mockMvc;

  @Test
  void 인증_없이_api_docs에_접근할_수_있고_19개_api가_전부_문서화돼_있다() throws Exception {
    String body =
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode root = new ObjectMapper().readTree(body);
    JsonNode paths = root.get("paths");
    assertThat(paths).isNotNull();

    int operationCount = 0;
    Iterator<String> pathNames = paths.fieldNames();
    while (pathNames.hasNext()) {
      JsonNode pathItem = paths.get(pathNames.next());
      Iterator<String> fieldNames = pathItem.fieldNames();
      while (fieldNames.hasNext()) {
        if (HTTP_METHODS.contains(fieldNames.next())) {
          operationCount++;
        }
      }
    }
    assertThat(operationCount).isEqualTo(19);

    JsonNode servers = root.get("servers");
    assertThat(servers).hasSize(1);
    assertThat(servers.get(0).get("url").asText()).isEqualTo("https://api.sairo.agency");
    assertThat(body).doesNotContain("localhost");

    assertThat(
            root.get("components").get("securitySchemes").has(OpenApiConfig.SESSION_COOKIE_SCHEME))
        .isTrue();
  }

  @Test
  void 인증_없이_swagger_ui_페이지에_접근할_수_있다() throws Exception {
    mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
  }

  @Test
  void 각_api의_문서화된_응답코드가_컨트롤러_서비스_코드가_실제로_던지는_응답코드와_정확히_일치한다() throws Exception {
    String body =
        mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
    JsonNode paths = new ObjectMapper().readTree(body).get("paths");

    Map<String, Set<String>> expectedByOperation =
        Map.ofEntries(
            Map.entry("GET /api/auth/kakao/start", Set.of("200")),
            Map.entry("GET /api/auth/kakao/callback", Set.of("200")),
            Map.entry("GET /api/home", Set.of("200", "401")),
            Map.entry("GET /api/properties", Set.of("200", "401")),
            Map.entry("GET /api/properties/duplicate-check", Set.of("200", "400", "401")),
            Map.entry("POST /api/properties", Set.of("201", "400", "401", "403")),
            Map.entry("GET /api/properties/{propertyId}", Set.of("200", "401", "404")),
            Map.entry(
                "PATCH /api/properties/{propertyId}", Set.of("200", "400", "401", "403", "404")),
            Map.entry(
                "POST /api/properties/{propertyId}/coordinations",
                Set.of("201", "400", "401", "403", "404")),
            Map.entry("GET /api/coordinations", Set.of("200", "401")),
            Map.entry("GET /api/coordinations/{coordinationId}", Set.of("200", "401", "404")),
            Map.entry(
                "POST /api/coordinations/{coordinationId}/buyers",
                Set.of("201", "401", "403", "404", "409")),
            Map.entry(
                "POST /api/coordinations/{coordinationId}/responses/{responseId}/restart",
                Set.of("200", "400", "401", "403", "404", "409", "422")),
            Map.entry(
                "POST /api/coordinations/{coordinationId}/confirm",
                Set.of("200", "400", "401", "403", "404", "409", "422")),
            Map.entry(
                "POST /api/coordinations/{coordinationId}/visit-complete",
                Set.of("200", "401", "403", "404", "409")),
            Map.entry(
                "POST /api/coordinations/{coordinationId}/cancel",
                Set.of("200", "401", "403", "404", "409")),
            Map.entry("GET /api/public/visit-responses/{token}", Set.of("200", "404")),
            Map.entry(
                "POST /api/public/visit-responses/{token}/available-times",
                Set.of("200", "400", "404", "409", "422")),
            Map.entry(
                "POST /api/public/visit-responses/{token}/no-availability",
                Set.of("200", "404", "409")));

    int checkedOperations = 0;
    Iterator<String> pathNames = paths.fieldNames();
    while (pathNames.hasNext()) {
      String path = pathNames.next();
      JsonNode pathItem = paths.get(path);
      Iterator<String> methodNames = pathItem.fieldNames();
      while (methodNames.hasNext()) {
        String method = methodNames.next();
        if (!HTTP_METHODS.contains(method)) {
          continue;
        }
        String operationKey = method.toUpperCase() + " " + path;
        Set<String> expected = expectedByOperation.get(operationKey);
        assertThat(expected).as("정의되지 않은 오퍼레이션: " + operationKey).isNotNull();

        Set<String> actual = new HashSet<>();
        Iterator<String> responseCodes = pathItem.get(method).get("responses").fieldNames();
        while (responseCodes.hasNext()) {
          actual.add(responseCodes.next());
        }
        assertThat(actual).as(operationKey).isEqualTo(expected);
        checkedOperations++;
      }
    }
    assertThat(checkedOperations).isEqualTo(expectedByOperation.size());
  }
}
