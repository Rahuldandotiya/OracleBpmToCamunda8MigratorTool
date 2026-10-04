package io.github.rahuldandotiya.o2c8.web;

import io.github.rahuldandotiya.o2c8.migration.Analysis;
import io.github.rahuldandotiya.o2c8.migration.Migration;
import io.github.rahuldandotiya.o2c8.util.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One browser session's files: uploads to convert ({@code migrate/}), uploaded finished migrations
 * ({@code kb/}), the last conversion output ({@code out/}) and the review checklist. Lives in a temp
 * folder and is deleted when idle for too long.
 */
final class Workspace {

  /** Upload areas. */
  enum Area {
    MIGRATE("migrate"), KB("kb");

    final String dir;

    Area(String dir) {
      this.dir = dir;
    }

    static Area of(String s) {
      return switch (s) {
        case "migrate" -> MIGRATE;
        case "kb", "knowledge-base" -> KB;
        default -> throw new WebError(400, "Unknown upload area '" + s + "' (use migrate or kb).");
      };
    }
  }

  /** Limits from application.properties. */
  record Limits(long maxBytes, int maxFiles) {}

  private static final Pattern SAFE_SEGMENT = Pattern.compile("[^/\\\\:*?\"<>|\\x00-\\x1f]{1,200}");

  final String id;
  final Path root;
  private final Limits limits;
  private volatile Instant lastUsed = Instant.now();
  private long bytes;
  private int files;

  // last results (guarded by this)
  Migration.Run run;
  Analysis.Result analysis;
  Map<String, Object> checklist = new LinkedHashMap<>();

  Workspace(String id, Path root, Limits limits) throws IOException {
    this.id = id;
    this.root = root;
    this.limits = limits;
    Files.createDirectories(root.resolve(Area.MIGRATE.dir));
    Files.createDirectories(root.resolve(Area.KB.dir));
  }

  void touch() {
    lastUsed = Instant.now();
  }

  Instant lastUsed() {
    return lastUsed;
  }

  Path area(Area a) {
    return root.resolve(a.dir);
  }

  Path out() {
    return root.resolve("out");
  }

  /** Stores one uploaded file at a relative path inside an area, enforcing path safety and limits. */
  synchronized String store(Area area, String relativePath, InputStream body) throws IOException {
    Path base = area(area);
    Path target = safeResolve(base, relativePath);
    if (files >= limits.maxFiles()) {
      throw new WebError(413, "Too many files: the limit is " + limits.maxFiles()
          + " per session (server.max-files). Upload a zip of the project instead.");
    }
    Files.createDirectories(target.getParent());
    long written = 0;
    Path tmp = Files.createTempFile(target.getParent(), ".upload", ".tmp");
    try (OutputStream os = Files.newOutputStream(tmp)) {
      byte[] buf = new byte[16384];
      int n;
      while ((n = body.read(buf)) > 0) {
        written += n;
        if (bytes + written > limits.maxBytes()) {
          throw new WebError(413, "Upload too large: the limit is " + (limits.maxBytes() >> 20)
              + " MB per session (server.max-upload-mb).");
        }
        os.write(buf, 0, n);
      }
    } catch (IOException | RuntimeException e) {
      Files.deleteIfExists(tmp);
      throw e;
    }
    Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    bytes += written;
    files++;
    return base.relativize(target).toString().replace('\\', '/');
  }

  /** Relative paths of the files in an area. */
  List<String> list(Area area) throws IOException {
    Path base = area(area);
    if (!Files.isDirectory(base)) {
      return List.of();
    }
    try (Stream<Path> s = Files.walk(base)) {
      return s.filter(Files::isRegularFile).map(p -> base.relativize(p).toString().replace('\\', '/'))
          .filter(p -> !p.contains("/.upload") && !p.startsWith(".upload")).sorted().toList();
    }
  }

  synchronized void clear(Area area) throws IOException {
    Path base = area(area);
    long removed = sizeOf(base);
    int count = list(area).size();
    deleteTree(base);
    Files.createDirectories(base);
    bytes = Math.max(0, bytes - removed);
    files = Math.max(0, files - count);
    if (area == Area.MIGRATE) {
      run = null;
      analysis = null;
    }
  }

  /** Resolves a client-supplied relative path, refusing anything that could leave {@code base}. */
  static Path safeResolve(Path base, String relativePath) {
    if (relativePath == null || relativePath.isBlank()) {
      throw new WebError(400, "Missing file path.");
    }
    String p = relativePath.replace('\\', '/');
    while (p.startsWith("/")) {
      p = p.substring(1);
    }
    List<String> parts = new ArrayList<>();
    for (String seg : p.split("/")) {
      if (seg.isEmpty() || seg.equals(".")) {
        continue;
      }
      if (seg.equals("..") || !SAFE_SEGMENT.matcher(seg).matches()) {
        throw new WebError(400, "Invalid file path '" + relativePath + "'.");
      }
      parts.add(seg);
    }
    if (parts.isEmpty() || parts.size() > 30) {
      throw new WebError(400, "Invalid file path '" + relativePath + "'.");
    }
    Path target = base.resolve(String.join("/", parts)).normalize();
    if (!target.startsWith(base.normalize())) {
      throw new WebError(400, "Invalid file path '" + relativePath + "'.");
    }
    return target;
  }

  Map<String, Object> describe() throws IOException {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", id);
    m.put("migrate", list(Area.MIGRATE));
    m.put("kb", list(Area.KB));
    m.put("bytes", bytes);
    m.put("hasResult", run != null);
    return m;
  }

  void saveChecklist() throws IOException {
    Files.writeString(root.resolve("checklist.json"), Json.write(checklist));
  }

  void delete() {
    try {
      deleteTree(root);
    } catch (IOException ignored) {
      // best effort; the OS cleans temp folders eventually
    }
  }

  static void deleteTree(Path p) throws IOException {
    if (!Files.exists(p)) {
      return;
    }
    try (Stream<Path> s = Files.walk(p)) {
      for (Path f : s.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(f);
      }
    }
  }

  private static long sizeOf(Path p) throws IOException {
    if (!Files.exists(p)) {
      return 0;
    }
    try (Stream<Path> s = Files.walk(p)) {
      return s.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
    }
  }
}
