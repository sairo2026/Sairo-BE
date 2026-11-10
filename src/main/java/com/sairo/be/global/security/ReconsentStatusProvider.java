package com.sairo.be.global.security;

public interface ReconsentStatusProvider {

  boolean isReconsentRequired(Long userId);
}
