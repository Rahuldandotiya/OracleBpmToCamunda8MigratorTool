package io.github.rahuldandotiya.o2c8.connectors;

import java.util.Locale;

/** Camunda secret names derived from Oracle reference names: PolicyService → POLICY_SERVICE_URL. */
public final class SecretNames {

  private SecretNames() {}

  public static String url(String referenceName) {
    String snake = referenceName
        .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
        .replaceAll("[^A-Za-z0-9]+", "_")
        .replaceAll("^_|_$", "")
        .toUpperCase(Locale.ROOT);
    return (snake.isEmpty() ? "SERVICE" : snake) + "_URL";
  }
}
