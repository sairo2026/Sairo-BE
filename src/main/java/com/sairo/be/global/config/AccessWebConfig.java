package com.sairo.be.global.config;

import com.sairo.be.global.security.access.AccessInterceptor;
import com.sairo.be.global.security.access.ReconsentInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Profile("!migrate")
@Configuration
public class AccessWebConfig implements WebMvcConfigurer {

  private final AccessInterceptor accessInterceptor;
  private final ReconsentInterceptor reconsentInterceptor;

  public AccessWebConfig(
      AccessInterceptor accessInterceptor, ReconsentInterceptor reconsentInterceptor) {
    this.accessInterceptor = accessInterceptor;
    this.reconsentInterceptor = reconsentInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(accessInterceptor).addPathPatterns("/api/**").order(0);
    registry.addInterceptor(reconsentInterceptor).addPathPatterns("/api/**").order(1);
  }
}
