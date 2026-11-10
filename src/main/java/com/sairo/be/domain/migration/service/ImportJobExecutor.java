package com.sairo.be.domain.migration.service;

public interface ImportJobExecutor {

  void submit(Runnable job);
}
