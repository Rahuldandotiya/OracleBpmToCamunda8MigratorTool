package io.github.rahuldandotiya.o2c8.convert;

import io.github.rahuldandotiya.o2c8.convert.elements.ActivityConverter;
import io.github.rahuldandotiya.o2c8.convert.elements.EventConverter;
import io.github.rahuldandotiya.o2c8.convert.elements.GatewayConverter;
import io.github.rahuldandotiya.o2c8.convert.elements.SubProcessConverter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import org.w3c.dom.Element;

/** Built-in converters plus any found on the class path via {@link ServiceLoader}. */
public final class ElementConverters {

  private final List<ElementConverter> converters;

  private ElementConverters(List<ElementConverter> converters) {
    List<ElementConverter> sorted = new ArrayList<>(converters);
    sorted.sort(Comparator.comparingInt(ElementConverter::priority).reversed());
    this.converters = List.copyOf(sorted);
  }

  public static ElementConverters load() {
    List<ElementConverter> all = new ArrayList<>(builtIns());
    ServiceLoader.load(ElementConverter.class).forEach(all::add);
    return new ElementConverters(all);
  }

  public static ElementConverters of(List<ElementConverter> extra) {
    List<ElementConverter> all = new ArrayList<>(builtIns());
    all.addAll(extra);
    return new ElementConverters(all);
  }

  private static List<ElementConverter> builtIns() {
    return List.of(
        new EventConverter(), new ActivityConverter(), new GatewayConverter(), new SubProcessConverter());
  }

  public Optional<ElementConverter> find(Element source) {
    return converters.stream().filter(c -> c.canConvert(source)).findFirst();
  }
}
