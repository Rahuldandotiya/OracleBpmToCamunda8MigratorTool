package io.github.rahuldandotiya.o2c8.web;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Settings from {@code application.properties}.
 *
 * <ul>
 *   <li>File lookup: {@code --config <file>} if given, else {@code ./application.properties} if it
 *       exists, else built-in defaults only.
 *   <li>Values may use {@code ${ENV_VAR}} or {@code ${ENV_VAR:default}} placeholders, so secrets can
 *       stay in the environment.
 *   <li>An environment variable named after the key always wins, e.g. {@code CAMUNDA_WEBMODELER_CLIENT_SECRET}
 *       for {@code camunda.webmodeler.client-secret}.
 * </ul>
 */
public final class AppConfig {

  public static final String DEFAULT_FILE = "application.properties";
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.\\-]+)(?::([^}]*))?}");

  private final Map<String, String> values;
  private final Path source;
  private final Function<String, String> env;

  private AppConfig(Map<String, String> values, Path source, Function<String, String> env) {
    this.values = values;
    this.source = source;
    this.env = env;
  }

  /** Loads the given file, or ./application.properties when {@code explicit} is null. */
  public static AppConfig load(Path explicit) throws IOException {
    return load(explicit, System::getenv);
  }

  static AppConfig load(Path explicit, Function<String, String> env) throws IOException {
    Path file = explicit != null ? explicit : Path.of(DEFAULT_FILE);
    if (explicit != null && !Files.isRegularFile(explicit)) {
      throw new IOException("Configuration file not found: " + explicit);
    }
    Map<String, String> m = new LinkedHashMap<>();
    if (Files.isRegularFile(file)) {
      Properties p = new Properties();
      try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        p.load(r);
      }
      p.stringPropertyNames().forEach(k -> m.put(k.trim(), p.getProperty(k).trim()));
      return new AppConfig(m, file.toAbsolutePath(), env);
    }
    return new AppConfig(m, null, env);
  }

  /** Configuration from a map (tests). */
  public static AppConfig of(Map<String, String> values) {
    return new AppConfig(new LinkedHashMap<>(values), null, k -> null);
  }

  /** The file the settings came from, or null when only defaults are used. */
  public Path source() {
    return source;
  }

  public String describeSource() {
    return source == null ? "no " + DEFAULT_FILE + " found (defaults only)" : source.toString();
  }

  /** Resolved value, or {@code fallback} when unset or blank. */
  public String get(String key, String fallback) {
    String fromEnv = env.apply(key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
    String v = fromEnv != null && !fromEnv.isBlank() ? fromEnv : values.get(key);
    if (v == null) {
      return fallback;
    }
    v = resolve(v).trim();
    return v.isEmpty() ? fallback : v;
  }

  public String get(String key) {
    return get(key, null);
  }

  public int getInt(String key, int fallback) {
    String v = get(key);
    try {
      return v == null ? fallback : Integer.parseInt(v);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Setting " + key + " must be a whole number, got '" + v + "'");
    }
  }

  /** All keys with a prefix, resolved (e.g. analyze.hours.*). */
  public Map<String, String> withPrefix(String prefix) {
    Map<String, String> m = new LinkedHashMap<>();
    values.keySet().stream().filter(k -> k.startsWith(prefix)).forEach(k -> m.put(k, get(k)));
    return Collections.unmodifiableMap(m);
  }

  private String resolve(String v) {
    Matcher m = PLACEHOLDER.matcher(v);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String name = m.group(1);
      String r = env.apply(name);
      if (r == null) {
        r = System.getProperty(name);
      }
      if (r == null) {
        r = m.group(2) == null ? "" : m.group(2);
      }
      m.appendReplacement(sb, Matcher.quoteReplacement(r));
    }
    m.appendTail(sb);
    return sb.toString();
  }
}
