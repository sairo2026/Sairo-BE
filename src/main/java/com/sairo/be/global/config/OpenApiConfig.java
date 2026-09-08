package com.sairo.be.global.config;

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
                    "사이로는 공인중개사가 세입자·구매희망자와 임장(현장 방문) 일정을 조율하는 서비스다. 이 문서는"
                        + " 사이로 1차 MVP 백엔드가 제공하는 API 18개를 기능별로 정리한다. 사무소 직원 전용 API는"
                        + " 카카오 로그인으로 발급되는 세션 쿠키가 필요하고, 공개 응답 API는 세입자·구매희망자에게"
                        + " 전달되는 URL 토큰만으로 인증 없이 호출한다.")
                .version("1.0.0")
                .contact(new Contact().name("SAIRO").email("sairowork26@gmail.com")))
        .servers(List.of(new Server().url("https://api.sairo.agency").description("운영 서버")))
        .components(
            new Components()
                .addSecuritySchemes(
                    SESSION_COOKIE_SCHEME,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name("__Host-sairo_session")
                        .description("카카오 로그인 성공 시 발급되는 사무소 직원 세션 쿠키다.")))
        .addSecurityItem(new SecurityRequirement().addList(SESSION_COOKIE_SCHEME));
  }
}
