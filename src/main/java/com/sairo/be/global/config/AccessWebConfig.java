package com.sairo.be.global.config;

import com.sairo.be.global.security.access.AccessInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Profile("!migrate")
@Configuration
public class AccessWebConfig implements WebMvcConfigurer {

  private final AccessInterceptor accessInterceptor;

  public AccessWebConfig(AccessInterceptor accessInterceptor) {
    this.accessInterceptor = accessInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(accessInterceptor).addPathPatterns("/api/**");
  }
}
