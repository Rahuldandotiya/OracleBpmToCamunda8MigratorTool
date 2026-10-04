package io.github.rahuldandotiya.o2c8.convert;

import org.w3c.dom.Element;

/**
 * Converts one kind of Oracle BPM flow element into its Camunda 8 equivalent.
 *
 * <p>This is the extension point (SPI). Register your own implementation in
 * {@code META-INF/services/io.github.rahuldandotiya.o2c8.convert.ElementConverter} to handle an
 * in-house Oracle construct or to override a built-in mapping; the converter with the highest
 * {@link #priority()} that {@link #canConvert(Element) accepts} an element wins.
 */
public interface ElementConverter {

  /** True if this converter handles the given source element (a {@code bpmn:*} DOM element). */
  boolean canConvert(Element source);

  /**
   * Creates the target element, appends it to {@code targetParent} and records what happened in
   * {@code ctx.report(...)}.
   *
   * @return the created element, or {@code null} when the element was intentionally dropped
   */
  Element convert(Element source, Element targetParent, ConversionContext ctx);

  /** Higher wins. Built-ins use 0; plugins that override a built-in should use a positive value. */
  default int priority() {
    return 0;
  }
}
