# Oracle BPM to Camunda 8 Migrator

Converts Oracle BPM 11g/12c process models (`.bpmn` exported from JDeveloper / BPM Studio) into
**executable Camunda 8 BPMN** and writes a migration report that lists what still needs a human.

Inspired by [camunda-consulting/migrate-to-camunda-tools](https://github.com/camunda-consulting/migrate-to-camunda-tools),
this tool goes further than a diagram copy:

- **Rebuilds the diagram.** Oracle exports have no `BPMNDiagram`; positions sit in Oracle extensions.
  The converter generates the pool, lanes, shapes and routed edges, so models open in Camunda Modeler.
- **Produces runnable models.** User tasks get `zeebe:userTask`, candidate groups (from lanes), and
  priority. XPath conditions and data assignments become FEEL conditions and `zeebe:ioMapping`.
  Signals, errors and messages are declared. Unconditional gateway branches become default flows.
- **Tells you what's left.** Every element is rated AUTO, PARTIAL or MANUAL in
  `conversion-report.md` / `.json`.
- **Zero runtime dependencies.** Only the JDK, so there are no third-party CVEs to track. XML parsing
  is hardened against XXE.

## Quick start

Requires Java 21.

```bash
mvn -B verify
java -jar migrator-cli/target/oracle2c8.jar convert samples/oracle-bpm-12c/insurance-claim -o out
```

```
  CatchDispatchEventProcess.bpmn           100% automated
  CustomerAcceptanceProcess.bpmn            80% automated
  ...
Wrote 8 model(s) and conversion-report.md to /…/out
```

Options:

| Option | Default | Meaning |
| --- | --- | --- |
| `-o, --output <dir>` | `camunda8-output` | Output folder |
| `--user-tasks camunda\|job-worker` | `camunda` | Camunda user tasks (8.5+) or job-worker user tasks |
| `--interface-events none\|message` | `none` | Oracle SOAP "define interface" start events become none start events, or message start events |
| `--platform-version <ver>` | `8.6.0` | Camunda version written into the models |
| `--fail-on-issues` | off | Exit code 2 if a model breaks a Camunda 8 rule (useful in CI) |

## What gets converted

| Oracle BPM | Camunda 8 | Level |
| --- | --- | --- |
| Start/end/intermediate events, gateways, sub-processes, event sub-processes | Same element | AUTO |
| Signal / error events (Oracle `eventRef` / `errorRef`) | `bpmn:signal` / `bpmn:error` with the Oracle names | AUTO |
| XPath sequence-flow conditions | FEEL conditions | AUTO (MANUAL placeholder if untranslatable) |
| Gateway branch without condition | `default` flow | AUTO |
| Data associations (`bpmn:getDataObject('x')/ns:a`) | `zeebe:ioMapping` (`=x.a`) | AUTO |
| Lanes / roles | Pool with lanes + `candidateGroups` on user tasks | AUTO |
| Human task (`.task` ref, priority 1-5) | `zeebe:userTask`, priority 100-0, task ref in documentation | PARTIAL (rebuild the form) |
| SOAP "define interface" start / reply end | None start / none end event | AUTO / PARTIAL |
| Service, send, script, business rule tasks | `zeebe:taskDefinition` with a job type from the name | PARTIAL (worker or connector) |
| Message catch events, receive tasks | Message with a correlation key placeholder | PARTIAL |
| Abstract task | Undefined (pass-through) task | PARTIAL |
| Oracle instance attributes (`organizationalUnit` …) | Process variable of the same name | INFO |
| Oracle-only settings (feature sets, log handlers, measurement marks) | Dropped | INFO |

## Project layout

```
migrator-core/   conversion engine (no dependencies)
  oracle/        reader for bpmnext:OracleExtensions
  expression/    XPath -> FEEL translator
  convert/       process walker + element converters (SPI)
  layout/        BPMN DI generator
  validate/      offline Camunda 8 rule checks
  report/        Markdown / JSON report
migrator-cli/    `oracle2c8` command line (shaded jar)
samples/         Oracle BPM 12c sample models used by the tests
```

## Extending

Add your own mapping for an in-house Oracle construct by implementing
`io.github.rahuldandotiya.o2c8.convert.ElementConverter` and listing it in
`META-INF/services/io.github.rahuldandotiya.o2c8.convert.ElementConverter`. Give it a
`priority()` above 0 to override a built-in converter.

## Tests

`mvn verify` runs:

- `XPathToFeelTest`: XPath to FEEL cases
- `SampleConversionTest`: converts every sample and checks the Camunda 8 rules
- `ZeebeDeploymentTest`: deploys every converted sample to an in-memory Zeebe engine
  ([zeebe-process-test](https://github.com/camunda/zeebe-process-test)) and runs scenarios end to end

## Roadmap

- Read whole Oracle projects: `composite.xml`, `.task` (assignment, outcomes, forms), `.rules` to DMN
- Analyze mode: inventory and effort estimate before converting
- Web UI and Docker image
- Camunda Marketplace listing

## License

Apache License 2.0
