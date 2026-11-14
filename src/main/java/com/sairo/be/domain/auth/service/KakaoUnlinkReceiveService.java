package com.sairo.be.domain.auth.service;

import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Profile("!migrate")
@Service
public class KakaoUnlinkReceiveService {

  private final KakaoUnlinkApplier applier;
  private final AuthMailComposer mailComposer;

  public KakaoUnlinkReceiveService(KakaoUnlinkApplier applier, AuthMailComposer mailComposer) {
    this.applier = applier;
    this.mailComposer = mailComposer;
  }

  public void receive(String appId, String kakaoUserId, String referrerType) {
    Optional<String> noticeEmail = applier.apply(appId, kakaoUserId, referrerType);
    noticeEmail.ifPresent(
        email ->
            mailComposer.sendNotice(
                email, "[사이로] 카카오 연결이 끊어졌습니다", "카카오 쪽에서 사이로 연결을 끊어 카카오 로그인을 더 이상 쓸 수 없습니다."));
  }
}
