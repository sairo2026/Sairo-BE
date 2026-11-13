package com.sairo.be.global.port.prod;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.services.pinpointsmsvoicev2.PinpointSmsVoiceV2Client;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sesv2.SesV2Client;

@Configuration
@Profile("prod")
class ProductionPortConfig {
  @Bean
  SesV2Client sesV2Client() {
    return SesV2Client.create();
  }

  @Bean
  PinpointSmsVoiceV2Client smsClient() {
    return PinpointSmsVoiceV2Client.create();
  }

  @Bean
  S3Client s3Client() {
    return S3Client.create();
  }

  @Bean
  S3Presigner s3Presigner() {
    return S3Presigner.create();
  }
}
