package com.sairo.be.global.security;

import java.io.Serializable;

/** 승인자 선택적 계정 세션의 주체다. 프로필 ID만 담고 번호 정보는 담지 않는다. */
public record ApproverPrincipal(Long profileId) implements Serializable {}
