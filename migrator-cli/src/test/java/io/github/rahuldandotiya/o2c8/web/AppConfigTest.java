package io.github.rahuldandotiya.o2c8.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppConfigTest {

  @Test
  void resolvesPlaceholdersAndEnvironmentOverrides(@TempDir Path tmp) throws Exception {
    Path f = tmp.resolve("application.properties");
    Files.writeString(f, """
        server.port=9090
        camunda.webmodeler.client-id=${WM_ID:fallback-id}
        camunda.webmodeler.client-secret=${WM_SECRET}
        camunda.webmodeler.audience=
        analyze.hours.form=2.5
        """);
    Map<String, String> env = Map.of("WM_SECRET", "s3cret", "SERVER_PORT", "7070");
    AppConfig c = AppConfig.load(f, env::get);
    assertEquals("fallback-id", c.get("camunda.webmodeler.client-id"));
    assertEquals("s3cret", c.get("camunda.webmodeler.client-secret"));
    assertEquals(7070, c.getInt("server.port", 8080), "environment variable wins over the file");
    assertNull(c.get("camunda.webmodeler.audience"), "blank means unset");
    assertEquals(Map.of("analyze.hours.form", "2.5"), c.withPrefix("analyze.hours."));
  }

  @Test
  void missingExplicitFileIsAnError(@TempDir Path tmp) {
    assertThrows(IOException.class, () -> AppConfig.load(tmp.resolve("nope.properties"), k -> null));
  }

  @Test
  void badNumberNamesTheSetting() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> AppConfig.of(Map.of("server.port", "eighty")).getInt("server.port", 8080));
    assertEquals("Setting server.port must be a whole number, got 'eighty'", e.getMessage());
  }
}
