package com.sairo.be.domain.auth.mapper;

import com.sairo.be.domain.auth.dto.request.KakaoUnlinkWebhookRequest;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Profile("!migrate")
@Component
public class KakaoUnlinkWebhookMapper {

  private final ObjectMapper objectMapper;

  public KakaoUnlinkWebhookMapper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public KakaoUnlinkWebhookRequest from(HttpServletRequest request) {
    String appId;
    String userId;
    String referrerType;
    String groupUserToken;
    if (isJson(request)) {
      JsonNode body = readBody(request);
      appId = text(body, "app_id", "appId");
      userId = text(body, "user_id", "userId");
      referrerType = text(body, "referrer_type", "referrerType");
      groupUserToken = text(body, "group_user_token", "groupUserToken");
    } else {
      appId = param(request, "app_id", "appId");
      userId = param(request, "user_id", "userId");
      referrerType = param(request, "referrer_type", "referrerType");
      groupUserToken = param(request, "group_user_token", "groupUserToken");
    }
    List<FieldErrorItem> errors = new ArrayList<>();
    require(errors, "appId", appId);
    require(errors, "userId", userId);
    require(errors, "referrerType", referrerType);
    if (!errors.isEmpty()) {
      throw BusinessException.withFieldErrors(ErrorCode.INVALID_REQUEST, errors);
    }
    return new KakaoUnlinkWebhookRequest(appId, userId, referrerType, groupUserToken);
  }

  private boolean isJson(HttpServletRequest request) {
    String contentType = request.getContentType();
    return contentType != null
        && MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_JSON);
  }

  private JsonNode readBody(HttpServletRequest request) {
    try {
      return objectMapper.readTree(request.getInputStream());
    } catch (IOException | RuntimeException e) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
  }

  private String text(JsonNode body, String snake, String camel) {
    JsonNode node = body.hasNonNull(snake) ? body.get(snake) : body.get(camel);
    if (node == null || node.isNull() || node.isArray() || node.isObject()) {
      return null;
    }
    return node.asString();
  }

  private String param(HttpServletRequest request, String snake, String camel) {
    String value = request.getParameter(snake);
    return value != null ? value : request.getParameter(camel);
  }

  private void require(List<FieldErrorItem> errors, String field, String value) {
    if (value == null || value.isBlank()) {
      errors.add(new FieldErrorItem(field, FieldErrorCode.REQUIRED, "필수 입력입니다."));
    }
  }
}
