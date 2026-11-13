package com.sairo.be.global.port.prod;

import com.sairo.be.global.port.AttachmentScanner;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
class ClamAvAttachmentScanner implements AttachmentScanner {
  private final String host;
  private final int port;

  ClamAvAttachmentScanner(
      @Value("${sairo.delivery.scanner.host}") String host,
      @Value("${sairo.delivery.scanner.port:3310}") int port) {
    this.host = host;
    this.port = port;
  }

  @Override
  public Verdict scan(String fileName, String mediaType, byte[] content) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 3000);
      socket.setSoTimeout(30000);
      DataOutputStream output = new DataOutputStream(socket.getOutputStream());
      output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
      int offset = 0;
      while (offset < content.length) {
        int chunk = Math.min(8192, content.length - offset);
        output.writeInt(chunk);
        output.write(content, offset, chunk);
        offset += chunk;
      }
      output.writeInt(0);
      output.flush();
      ByteArrayOutputStream responseBytes = new ByteArrayOutputStream();
      for (int next; (next = socket.getInputStream().read()) > 0; ) {
        if (responseBytes.size() >= 4096) {
          throw new IllegalStateException("첨부 파일 검사 응답이 너무 깁니다.");
        }
        responseBytes.write(next);
      }
      String response = responseBytes.toString(StandardCharsets.US_ASCII).trim();
      if (response.endsWith("OK")) {
        return Verdict.CLEAN;
      }
      if (response.contains("FOUND")) {
        return Verdict.REJECTED;
      }
      throw new IllegalStateException("첨부 파일 검사에 실패했습니다.");
    } catch (IOException e) {
      throw new IllegalStateException("첨부 파일 검사기에 연결할 수 없습니다.", e);
    }
  }
}
