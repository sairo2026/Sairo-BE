package com.sairo.be.global.config;

import com.sairo.be.global.error.ErrorResponse;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Profile("!migrate")
@Configuration
public class OpenApiConfig {

  public static final String SESSION_COOKIE_SCHEME = "sairoSessionCookie";

  @Bean
  OpenAPI sairoOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("사이로 백엔드 API")
                .description(
                    "사이로는 부동산 중개사무소가 매물, 고객, 임장 조율, 계약, 월말결산, 구독·결제,"
                        + " 데이터 이전을 사무소 단위로 운영하는 서비스다. 내부 API는 세션 쿠키와 CSRF 토큰을 쓰고,"
                        + " 공개 링크 API는 URL 토큰만 쓴다. 모든 오류 응답은 code, message, traceId를 담는다.")
                .version("1.0.0")
                .contact(new Contact().name("SAIRO").email("sairowork26@gmail.com")))
        .servers(List.of(new Server().url("https://api.sairo.agency").description("운영 서버")))
        .components(
            errorSchemas(
                new Components()
                    .addSecuritySchemes(
                        SESSION_COOKIE_SCHEME,
                        new SecurityScheme()
                            .type(SecurityScheme.Type.APIKEY)
                            .in(SecurityScheme.In.COOKIE)
                            .name("__Host-sairo_session")
                            .description("로그인 성공 시 발급되는 사무소 구성원 세션 쿠키다."))))
        .addSecurityItem(new SecurityRequirement().addList(SESSION_COOKIE_SCHEME));
  }

  private Components errorSchemas(Components components) {
    ModelConverters.getInstance().readAll(ErrorResponse.class).forEach(components::addSchemas);
    return components;
  }
}
