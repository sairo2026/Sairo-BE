package com.sairo.be.global.port.toss;

public class TossGatewayException extends RuntimeException {

  public TossGatewayException(String message, Throwable cause) {
    super(message, cause);
  }

  public TossGatewayException(String message) {
    super(message);
  }
}
