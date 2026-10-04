package io.github.rahuldandotiya.o2c8.web;

/** A Web Modeler upload problem, with a message that tells the user what to fix. */
public final class WebModelerException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** What went wrong; the web UI maps it to an HTTP status and a hint. */
  public enum Kind {
    NOT_CONFIGURED(503),
    UNREACHABLE(502),
    AUTHENTICATION(502),
    PERMISSION(502),
    NOT_FOUND(502),
    CONFLICT(409),
    BAD_REQUEST(400),
    API_ERROR(502);

    private final int httpStatus;

    Kind(int httpStatus) {
      this.httpStatus = httpStatus;
    }

    public int httpStatus() {
      return httpStatus;
    }
  }

  private final Kind kind;

  public WebModelerException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
