# Oracle BPM to Camunda 8 Migrator

Converts Oracle BPM 11g/12c process models, and whole Oracle SOA/BPM projects, into
**executable Camunda 8 BPMN**. It writes a migration report that lists what still needs a human.
It can also **learn from processes your team has already migrated**, so every new conversion
starts closer to done.

Inspired by [camunda-consulting/migrate-to-camunda-tools](https://github.com/camunda-consulting/migrate-to-camunda-tools),
this tool goes further than a diagram copy:

- **Rebuilds the diagram.** Oracle exports have no `BPMNDiagram`; positions sit in Oracle extensions.
  The converter generates the pool, lanes, shapes and routed edges, so models open in Camunda Modeler.
- **Produces runnable models.** User tasks get `zeebe:userTask`, candidate groups and priority.
  XPath becomes FEEL conditions and `zeebe:ioMapping`. Signals, errors and messages are declared,
  and gateway defaults are set.
- **Reads `composite.xml`.** Service tasks are followed to their SCA binding:
  - REST references become the **Camunda REST connector**, with URL, method, path parameters,
    body and result expression.
  - SOAP references become workers carrying the WSDL details.
  - DB/JMS/file adapters become workers carrying the adapter settings.
  - Calls to other BPMN components become **call activities**.
  - Endpoints become **Camunda secrets**, with production values taken from config plans.
- **Learns from finished migrations.** Drop Oracle processes together with the Camunda models your
  team finished for them. The tool learns the team's decisions and reapplies them:
  - connector and worker settings per called service,
  - forms and groups per Oracle human task,
  - variable names, rewritten conditions, and naming conventions.
- **Tells you what's left.** Every element is rated AUTO, PARTIAL or MANUAL, with where the decision
  came from (built-in, composite or knowledge base).
- **Zero runtime dependencies.** Only the JDK. XML parsing is hardened against XXE, and archive
  reading against zip-slip and zip bombs.

## Quick start

Requires Java 21 and Maven.

```bash
mvn -B verify                       # build and run all tests
cd samples/demo
java -jar ../../migrator-cli/target/oracle2c8.jar migrate
```

```
Knowledge base knowledge-base: 5 pair(s), 36 rule(s).
  1 composite.xml file(s) found: service calls are configured from them.
  CustomerAcceptanceProcess.bpmn                         80% automated, 2 from knowledge base
  RejectionHandlerProcess.bpmn                          100% automated, 2 from knowledge base
  claim-settlement/.../ClaimSettlementProcess.bpmn       57% automated, 5 from knowledge base
  claim-settlement/.../PaymentProcess.bpmn               33% automated, 1 from knowledge base
Wrote 4 model(s) and conversion-report.md to .../camunda8-output
```

In IntelliJ, use the shared run configurations **Migrate demo (samples/demo)**, **Migrate
(knowledge-base + processToMigrate)** and **Evaluate demo knowledge base**.

## How to use it on your own processes

```
knowledge-base/      finished migrations: Oracle files/projects + the Camunda 8 models your team completed
processToMigrate/    Oracle .bpmn files, projects with composite.xml, or .zip/.sar archives to convert
camunda8-output/     result: converted models (same folder structure), conversion-report.md, knowledge-base.json
```

1. Put what you want to convert in `processToMigrate/`.
2. Optionally put finished migrations in `knowledge-base/`. Without them, the built-in conversion runs.
3. Run `oracle2c8 migrate` from the repository folder.

Both drop folders are git-ignored (except their READMEs), so client models stay on your machine.

### Commands

| Command | What it does |
| --- | --- |
| `oracle2c8 migrate` | Learns from `./knowledge-base`, converts `./processToMigrate` into `./camunda8-output` |
| `oracle2c8 convert <files/folders> [--kb <folder or json>]` | Converts anything you point it at |
| `oracle2c8 learn <folder> -o knowledge-base.json` | Builds a reviewable knowledge-base file |
| `oracle2c8 evaluate <folder>` | Leave-one-out test: how many edits the knowledge base saves |

Options: `--kb`, `--in`, `-o`, `--no-kb`, `--user-tasks camunda|job-worker`,
`--interface-events none|message`, `--platform-version 8.6.0`, `--fail-on-issues`.

## The knowledge base

For each Oracle process in `knowledge-base/`, the tool finds the finished Camunda model:
same process id, else same file name, else mostly the same element ids, else the same process name.
It converts the Oracle side itself and compares the result with the finished model, element by
element. Every difference is saved as a rule, under a key that recurs in other processes:

| Rule | Key | Example |
| --- | --- | --- |
| Service call | composite reference + operation, or the endpoint | `FraudService.checkFraud` → worker `claims.soap-…`, 5 retries |
| Human task | Oracle `.task` name | `ValidationUserTask` → form `validation-user-task-form`, group `claims-case-manager` |
| Condition | normalised XPath text | → the FEEL the team wrote |
| Variable | data object name, or type + Oracle suffix | `Claim` + `INPDO` → `claim` |
| Role | lane / role name | `CSR` → `claims-csr` |
| Message | message name | → name + correlation key |
| Convention (needs 2+ pairs) | naming patterns | job type prefix `claims.`, group prefix `claims-`, form id `{name}-form` |

Rules only apply on exact key matches, with most specific first:
exact element → service call / human task → convention → composite → built-in.
Pairs that disagree are flagged PARTIAL, with the majority value applied. On the demo, leave-one-out
evaluation shows **74% fewer manual edits** (50 → 13).

## What gets converted

| Oracle BPM | Camunda 8 | Level |
| --- | --- | --- |
| Events, gateways, sub-processes, event sub-processes | Same element | AUTO |
| Signal / error events | `bpmn:signal` / `bpmn:error` with the Oracle names | AUTO |
| XPath conditions and data assignments | FEEL conditions, `zeebe:ioMapping` | AUTO (MANUAL placeholder if untranslatable) |
| Lanes / roles | Pool with lanes + `candidateGroups` | AUTO |
| Human task (`.task`, priority 1–5) | `zeebe:userTask`, priority 100–0 | PARTIAL (form) |
| Service task → REST reference | REST outbound connector | AUTO |
| Service task → SOAP reference | Job worker with endpoint/SOAP action headers | PARTIAL |
| Service task → DB/JMS/file adapter | Job worker with adapter settings | PARTIAL |
| Service task → BPMN component | Call activity | AUTO |
| Service task, no composite | Job worker named after the task | PARTIAL |
| SOAP "define interface" start / reply end | None start / none end event | AUTO / PARTIAL |
| Message catch events, receive tasks | Message with a correlation key placeholder | PARTIAL |

## Project layout

```
migrator-core/   conversion engine (no dependencies)
  oracle/        reader for bpmnext:OracleExtensions
  expression/    XPath -> FEEL
  convert/       process walker + element converters (SPI)
  project/       drop-folder loader (folders, zip/SAR, Oracle vs Camunda detection)
  composite/     composite.xml, WADL, WSDL, JCA, config plans, service call resolution
  connectors/    REST connector / worker / call activity mapping, secret names
  knowledge/     pairing, learning, applying, evaluation, knowledge-base JSON
  layout/        BPMN DI generator
  validate/      offline Camunda 8 rule checks
  report/        Markdown / JSON report
migrator-cli/    `oracle2c8` command line (shaded jar)
samples/         Oracle BPM 12c samples; samples/demo = knowledge-base + processToMigrate example
```

`samples/demo` contains synthetic, hand-written Oracle projects (`composite.xml`, WADL, WSDL,
JCA) in Oracle 12c style, plus example "finished" Camunda models, used by the tests.

## Extending

Implement `io.github.rahuldandotiya.o2c8.convert.ElementConverter` and register it in
`META-INF/services/io.github.rahuldandotiya.o2c8.convert.ElementConverter`. Give it a
`priority()` above 0 to override a built-in converter.

## Tests

`mvn verify` runs the XPath→FEEL cases, sample conversions, composite mapping, knowledge-base
learning/applying/evaluation, archive safety, and the CLI. `ZeebeDeploymentTest` deploys every
converted model, including the migrated demo, to an in-memory Zeebe engine
([zeebe-process-test](https://github.com/camunda/zeebe-process-test)) and runs scenarios end to end.

## License

Apache License 2.0
