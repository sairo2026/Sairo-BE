package com.sairo.be.global.port.prod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sairo.be.global.port.AttachmentScanner.Verdict;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ClamAvAttachmentScannerTest {
  @Test
  void acceptsCleanAndRejectsDetectedFile() throws Exception {
    assertEquals(Verdict.CLEAN, scanWithReply("stream: OK\0"));
    assertEquals(Verdict.REJECTED, scanWithReply("stream: Eicar-Test-Signature FOUND\0"));
  }

  @Test
  void scannerErrorsFailClosed() throws Exception {
    assertThrows(
        IllegalStateException.class, () -> scanWithReply("stream: size limit exceeded ERROR\0"));
  }

  private Verdict scanWithReply(String reply) throws Exception {
    try (ServerSocket server = new ServerSocket(0)) {
      CompletableFuture<Void> responder =
          CompletableFuture.runAsync(
              () -> {
                try (var connection = server.accept()) {
                  DataInputStream input = new DataInputStream(connection.getInputStream());
                  byte[] command = new byte[10];
                  input.readFully(command);
                  assertEquals("zINSTREAM\0", new String(command, StandardCharsets.US_ASCII));
                  int size;
                  while ((size = input.readInt()) > 0) {
                    input.skipNBytes(size);
                  }
                  connection.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII));
                  connection.getOutputStream().flush();
                } catch (IOException error) {
                  throw new RuntimeException(error);
                }
              });
      Verdict result =
          new ClamAvAttachmentScanner("127.0.0.1", server.getLocalPort())
              .scan("sample.txt", "text/plain", "hello".getBytes(StandardCharsets.US_ASCII));
      responder.join();
      return result;
    }
  }
}
