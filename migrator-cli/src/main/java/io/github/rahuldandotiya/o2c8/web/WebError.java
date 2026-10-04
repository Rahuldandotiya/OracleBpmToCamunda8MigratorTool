package io.github.rahuldandotiya.o2c8.web;

/** An error answered to the browser with an HTTP status and a readable message. */
final class WebError extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int status;

  WebError(int status, String message) {
    super(message);
    this.status = status;
  }

  int status() {
    return status;
  }
}
