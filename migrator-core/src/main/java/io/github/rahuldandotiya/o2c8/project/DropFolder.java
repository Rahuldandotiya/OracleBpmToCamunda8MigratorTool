package io.github.rahuldandotiya.o2c8.project;

import io.github.rahuldandotiya.o2c8.composite.Composite;
import io.github.rahuldandotiya.o2c8.composite.CompositeReader;
import io.github.rahuldandotiya.o2c8.xml.Ns;
import io.github.rahuldandotiya.o2c8.xml.XmlUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Everything found in one drop folder ({@code knowledge-base/} or {@code processToMigrate/}): loose
 * {@code .bpmn} files, whole JDeveloper projects (with {@code composite.xml}) and zip/SAR archives
 * of them. Files are grouped by their first-level sub-folder; a composite applies to every Oracle
 * process below its own folder.
 */
public final class DropFolder {

  /** Archive limits: protect against zip bombs and path traversal. */
  static final long MAX_ARCHIVE_BYTES = 200L * 1024 * 1024;

  static final int MAX_ARCHIVE_ENTRIES = 20_000;

  private final Path root;
  private final List<ProcessFile> processes = new ArrayList<>();
  private final List<Composite> composites = new ArrayList<>();
  private final List<String> warnings = new ArrayList<>();
  private final List<Unreadable> unreadable = new ArrayList<>();
  private final List<Path> tempDirs = new ArrayList<>();

  private DropFolder(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  /** Loads a folder, or a single file (.bpmn / .zip / .sar), treating it as the whole drop. */
  public static DropFolder load(Path folderOrFile) throws IOException {
    DropFolder d = new DropFolder(Files.isDirectory(folderOrFile) ? folderOrFile : folderOrFile.getParent());
    if (Files.isDirectory(folderOrFile)) {
      d.scan(d.root, d.root, "");
    } else {
      d.add(folderOrFile.toAbsolutePath().normalize(), d.root, "");
    }
    return d;
  }

  /** Loads several inputs (files or folders) as one drop, e.g. from the command line. */
  public static DropFolder load(List<Path> inputs) throws IOException {
    if (inputs.size() == 1) {
      return load(inputs.get(0));
    }
    DropFolder d = new DropFolder(Path.of("").toAbsolutePath());
    for (Path p : inputs) {
      Path abs = p.toAbsolutePath().normalize();
      if (Files.isDirectory(abs)) {
        d.scan(abs, abs, abs.getFileName().toString());
      } else if (Files.isRegularFile(abs)) {
        d.add(abs, abs.getParent(), "");
      } else {
        throw new IOException("Not found: " + p);
      }
    }
    return d;
  }

  public Path root() {
    return root;
  }

  public List<ProcessFile> processes() {
    return processes;
  }

  public List<ProcessFile> processes(ProcessFile.Kind kind) {
    return processes.stream().filter(p -> p.kind() == kind).toList();
  }

  public List<Composite> composites() {
    return composites;
  }

  public List<String> warnings() {
    return warnings;
  }

  /** A .bpmn file that could not be read (broken XML, unsafe content ...), with the reason. */
  public record Unreadable(String displayPath, String reason) {}

  /** Process files that were found but could not be parsed; reported as failed conversions. */
  public List<Unreadable> unreadable() {
    return unreadable;
  }

  /** The composite whose folder is the closest ancestor of this process file. */
  public Optional<Composite> compositeFor(ProcessFile f) {
    return composites.stream()
        .filter(c -> f.path().startsWith(c.root()))
        .max(Comparator.comparingInt(c -> c.root().getNameCount()));
  }

  /** Deletes folders created for extracted archives. */
  public void close() {
    for (Path t : tempDirs) {
      try (Stream<Path> s = Files.walk(t)) {
        s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      } catch (IOException ignored) {
        // best effort
      }
    }
  }

  // ------------------------------------------------------------------ scanning

  private void scan(Path dir, Path base, String groupPrefix) throws IOException {
    List<Path> files;
    try (Stream<Path> s = Files.walk(dir, 30)) {
      files = s.filter(Files::isRegularFile).sorted().toList();
    }
    for (Path f : files) {
      add(f.toAbsolutePath().normalize(), base, groupPrefix);
    }
  }

  private void add(Path f, Path base, String groupPrefix) throws IOException {
    String name = f.getFileName().toString();
    String lower = name.toLowerCase(Locale.ROOT);
    if (name.startsWith(".") || isGenerated(f)) {
      return;
    }
    Path rel = base.relativize(f);
    String group = groupPrefix.isEmpty()
        ? (rel.getNameCount() > 1 ? rel.getName(0).toString() : "")
        : groupPrefix;
    try {
      if (lower.endsWith(".bpmn") || lower.endsWith(".bpmn2")) {
        String display = (groupPrefix.isEmpty() ? rel.toString() : groupPrefix + "/" + rel).replace('\\', '/');
        try {
          classify(f, display, group).ifPresent(processes::add);
        } catch (IOException | RuntimeException e) {
          String m = String.valueOf(e.getMessage());
          unreadable.add(new Unreadable(display, m.contains("DOCTYPE")
              ? "the file contains a DOCTYPE declaration, which is refused for security (XML external entity attacks)"
              : "the file could not be read as BPMN XML: " + m.replace("Not a well-formed XML document: ", "")));
        }
      } else if (lower.equals("composite.xml")) {
        composites.add(CompositeReader.read(f));
      } else if (lower.endsWith(".zip") || lower.endsWith(".sar") || lower.endsWith(".jar")) {
        Path out = extract(f);
        String archiveGroup = group.isEmpty() ? stripExt(name) : group;
        scan(out, out, archiveGroup);
      }
    } catch (IOException | RuntimeException e) {
      warnings.add(rel + ": skipped (" + e.getMessage() + ")");
    }
  }

  /** Our own output (or JDeveloper build folders) must never be read back as input. */
  private static boolean isGenerated(Path f) {
    for (Path part : f) {
      String p = part.toString();
      if (p.equals("camunda8-output") || p.equals("target") || p.equals("deploy") || p.equals(".adf")) {
        return true;
      }
    }
    return false;
  }

  static Optional<ProcessFile> classify(Path f, String display, String group) throws IOException {
    Document doc = XmlUtils.parse(f);
    Element defs = doc.getDocumentElement();
    if (!Ns.BPMN.equals(defs.getNamespaceURI())) {
      return Optional.empty();
    }
    boolean camunda = hasNamespace(defs, Ns.ZEEBE)
        || "Camunda Cloud".equals(defs.getAttributeNS(Ns.MODELER, "executionPlatform"))
        || !XmlUtils.descendants(defs, Ns.ZEEBE, null).isEmpty();
    boolean oracle = hasNamespace(defs, Ns.ORACLE) || !XmlUtils.descendants(defs, Ns.ORACLE, null).isEmpty();
    ProcessFile.Kind kind = camunda ? ProcessFile.Kind.CAMUNDA8
        : oracle ? ProcessFile.Kind.ORACLE : ProcessFile.Kind.OTHER_BPMN;
    List<String> ids = new ArrayList<>();
    List<String> names = new ArrayList<>();
    for (Element p : XmlUtils.children(defs, Ns.BPMN, "process")) {
      ids.add(p.getAttribute("id"));
      names.add(XmlUtils.attr(p, "name"));
    }
    return Optional.of(new ProcessFile(f, display, group, kind, ids, names));
  }

  private static boolean hasNamespace(Element e, String ns) {
    var attrs = e.getAttributes();
    for (int i = 0; i < attrs.getLength(); i++) {
      if (ns.equals(attrs.item(i).getNodeValue())) {
        return true;
      }
    }
    return false;
  }

  /** Extracts an archive into a temp folder, refusing path traversal and oversized content. */
  private Path extract(Path archive) throws IOException {
    Path out = Files.createTempDirectory("oracle2c8-");
    tempDirs.add(out);
    long total = 0;
    int entries = 0;
    try (InputStream in = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(in)) {
      ZipEntry e;
      byte[] buf = new byte[8192];
      while ((e = zip.getNextEntry()) != null) {
        if (++entries > MAX_ARCHIVE_ENTRIES) {
          throw new IOException("archive has too many entries");
        }
        Path target = out.resolve(e.getName()).normalize();
        if (!target.startsWith(out)) {
          throw new IOException("archive entry escapes the target folder: " + e.getName());
        }
        if (e.isDirectory()) {
          Files.createDirectories(target);
          continue;
        }
        Files.createDirectories(target.getParent());
        try (var os = Files.newOutputStream(target)) {
          int n;
          while ((n = zip.read(buf)) > 0) {
            total += n;
            if (total > MAX_ARCHIVE_BYTES) {
              throw new IOException("archive expands beyond " + (MAX_ARCHIVE_BYTES >> 20) + " MB");
            }
            os.write(buf, 0, n);
          }
        }
      }
    }
    return out;
  }

  private static String stripExt(String n) {
    int dot = n.lastIndexOf('.');
    return dot > 0 ? n.substring(0, dot) : n;
  }
}
