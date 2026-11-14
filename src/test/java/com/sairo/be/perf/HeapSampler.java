package com.sairo.be.perf;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;

final class HeapSampler implements AutoCloseable {

  record Peak(long sampledUsedBytes, long oldGenPeakBytes, long maxHeapBytes) {

    String describe() {
      return "표본 최대 사용 "
          + sampledUsedBytes / (1024 * 1024)
          + "MB, 노년 영역 최대 "
          + oldGenPeakBytes / (1024 * 1024)
          + "MB, 힙 한도 "
          + maxHeapBytes / (1024 * 1024)
          + "MB";
    }
  }

  private final Thread thread;
  private volatile boolean running = true;
  private volatile long peakUsed;

  HeapSampler() {
    System.gc();
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if (pool.getType() == MemoryType.HEAP) {
        pool.resetPeakUsage();
      }
    }
    thread =
        new Thread(
            () -> {
              while (running) {
                long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                if (used > peakUsed) {
                  peakUsed = used;
                }
                try {
                  Thread.sleep(20);
                } catch (InterruptedException e) {
                  return;
                }
              }
            },
            "heap-sampler");
    thread.setDaemon(true);
    thread.start();
  }

  Peak stop() {
    running = false;
    long oldGen = 0;
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if (pool.getType() == MemoryType.HEAP && pool.getName().contains("Old")) {
        oldGen = Math.max(oldGen, pool.getPeakUsage().getUsed());
      }
    }
    long max = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
    return new Peak(peakUsed, oldGen, max);
  }

  @Override
  public void close() {
    running = false;
  }
}
