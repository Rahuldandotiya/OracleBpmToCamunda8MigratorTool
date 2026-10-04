package io.github.rahuldandotiya.o2c8.convert.elements;

import io.github.rahuldandotiya.o2c8.xml.Ns;
import io.github.rahuldandotiya.o2c8.xml.XmlUtils;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Element;

/**
 * Oracle BPM timer schedules ({@code bpmnext:Schedule}) to Zeebe cron expressions. Zeebe timer
 * cycles accept six-field cron expressions (seconds first), e.g. {@code 0 28 22 * * *}.
 */
final class TimerSchedules {

  record Cron(String expression, boolean exact, String description) {}

  private static final Pattern TIME_OF_DAY = Pattern.compile("^PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?$");
  private static final List<String> DAYS = List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");

  private TimerSchedules() {}

  static Cron toCron(Element schedule) {
    List<int[]> times = new ArrayList<>();
    Set<String> weekdays = new LinkedHashSet<>();
    Set<String> monthDays = new LinkedHashSet<>();
    String kind = "daily";
    boolean unknown = false;
    for (Element entries : XmlUtils.children(schedule, Ns.ORACLE, null)) {
      String ln = entries.getLocalName().toLowerCase(Locale.ROOT);
      if (ln.startsWith("weekly")) {
        kind = "weekly";
      } else if (ln.startsWith("monthly")) {
        kind = "monthly";
      } else if (!ln.startsWith("daily")) {
        unknown = true;
      }
      for (Element d : XmlUtils.descendants(entries, Ns.ORACLE, null)) {
        String text = XmlUtils.ownText(d).trim();
        Matcher m = TIME_OF_DAY.matcher(text);
        if (m.matches() && !text.equals("PT")) {
          times.add(new int[] {num(m.group(3)), num(m.group(2)), num(m.group(1))});
        } else if (DAYS.contains(text.toUpperCase(Locale.ROOT))) {
          weekdays.add(text.substring(0, 3).toUpperCase(Locale.ROOT));
        } else if (text.matches("\\d{1,2}") && kind.equals("monthly")) {
          monthDays.add(text);
        }
      }
    }
    if (times.isEmpty()) {
      return new Cron("0 0 0 * * *", false, "no time found in the Oracle schedule; midnight used as placeholder");
    }
    int[] t = times.get(0);
    boolean sameMinute = times.stream().allMatch(x -> x[0] == t[0] && x[1] == t[1]);
    String hours = sameMinute ? String.join(",", times.stream().map(x -> String.valueOf(x[2])).distinct().toList())
        : String.valueOf(t[2]);
    String dom = kind.equals("monthly") && !monthDays.isEmpty() ? String.join(",", monthDays) : "*";
    String dow = kind.equals("weekly") && !weekdays.isEmpty() ? String.join(",", weekdays) : "*";
    String expr = t[0] + " " + t[1] + " " + hours + " " + dom + " * " + dow;
    boolean exact = !unknown && (sameMinute || times.size() == 1)
        && (!kind.equals("weekly") || !weekdays.isEmpty()) && (!kind.equals("monthly") || !monthDays.isEmpty());
    String desc = kind + " at " + String.format("%02d:%02d", t[2], t[1])
        + (times.size() > 1 ? (sameMinute ? " (several hours)" : " (first of " + times.size() + " times)") : "")
        + (kind.equals("weekly") ? " on " + String.join(",", weekdays) : "")
        + (kind.equals("monthly") ? " on day " + String.join(",", monthDays) : "");
    return new Cron(expr, exact, desc);
  }

  /** "start .. end" from Oracle's OptionalTimerEventFeatures, or null. */
  static String window(Element timerDefinition) {
    String start = text(timerDefinition, "startDate");
    String end = text(timerDefinition, "endDate");
    if (start == null && end == null) {
      return null;
    }
    return (start == null ? "…" : start) + " to " + (end == null ? "…" : end);
  }

  private static String text(Element e, String ln) {
    return XmlUtils.descendants(e, Ns.ORACLE, ln).stream().map(XmlUtils::ownText).filter(s -> !s.isBlank())
        .findFirst().orElse(null);
  }

  private static int num(String s) {
    return s == null ? 0 : Integer.parseInt(s);
  }
}
