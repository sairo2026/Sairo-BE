package com.sairo.be.global.port.prod;

import com.sairo.be.global.port.ObjectStorage;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Component
@Profile("prod")
class S3ObjectStorage implements ObjectStorage {
  private final S3Client client;
  private final S3Presigner presigner;
  private final String bucket;

  S3ObjectStorage(
      S3Client client,
      S3Presigner presigner,
      @Value("${sairo.delivery.storage.bucket}") String bucket) {
    this.client = client;
    this.presigner = presigner;
    this.bucket = bucket;
  }

  @Override
  public void put(String key, byte[] content, String contentType) {
    client.putObject(
        PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
        RequestBody.fromBytes(content));
  }

  @Override
  public Optional<byte[]> find(String key) {
    try {
      return Optional.of(
          client
              .getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
              .asByteArray());
    } catch (NoSuchKeyException missing) {
      return Optional.empty();
    }
  }

  @Override
  public URI issueDownloadUrl(String key, Duration timeToLive) {
    if (timeToLive.isNegative()
        || timeToLive.isZero()
        || timeToLive.compareTo(Duration.ofDays(7)) > 0) {
      throw new IllegalArgumentException("다운로드 링크의 유효기간이 올바르지 않습니다.");
    }
    return URI.create(
        presigner
            .presignGetObject(
                GetObjectPresignRequest.builder()
                    .signatureDuration(timeToLive)
                    .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .build())
            .url()
            .toString());
  }

  @Override
  public void delete(String key) {
    client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
  }
}
