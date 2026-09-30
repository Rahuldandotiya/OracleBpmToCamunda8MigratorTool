package io.github.rahuldandotiya.o2c8.convert;

/** Package bridge so the top-level converter can finish a context without widening its API. */
public final class ConversionContextAccess {

  private ConversionContextAccess() {}

  /** Writes collected messages, signals and errors (with subscriptions) into the definitions. */
  public static void writeRootElements(ConversionContext ctx) {
    ctx.writeRootElements(ctx.subscriptions());
  }
}
