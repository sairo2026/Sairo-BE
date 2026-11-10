package com.sairo.be.devseed;

import com.sairo.be.SairoBeApplication;
import com.sairo.be.TestcontainersConfiguration;
import org.springframework.boot.SpringApplication;

public class TestSairoBeReviewApplication {

  public static void main(String[] args) {
    SpringApplication.from(SairoBeApplication::main)
        .with(TestcontainersConfiguration.class, ReviewSeedConfiguration.class)
        .run(args);
  }
}
