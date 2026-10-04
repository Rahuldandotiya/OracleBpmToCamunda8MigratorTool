# Chapter03 – Oracle BPM 12c → Camunda 8 (visual check)

Source: the `Chapter03/LoanOriginationProcess` project from the public
[PacktPublishing/Oracle-BPM-Suite-12c-Modeling-Patterns](https://github.com/PacktPublishing/Oracle-BPM-Suite-12c-Modeling-Patterns) repository
(converted with `oracle2c8 convert`, composite.xml-aware, no knowledge base). The Packt sources are **not** stored in this repo – only these images.

| Folder | What it shows |
|---|---|
| `oracle/` | The Oracle process as authored, drawn straight from Oracle's `bpmnext:GraphicsAttributes` (Studio-like styling, independent of the migrator) |
| `camunda8/` | The converted Camunda 8 BPMN, rendered with bpmn-js (what Camunda Modeler shows) |
| `compare/` | Side by side, with the automation stats and every PARTIAL/MANUAL item still to review |

## Result

**9 / 9 processes converted, 0 Zeebe validation issues, 0 MANUAL items.**
Elements: 32 automatic, 21 partial (need a decision), 0 manual → 60% fully automatic.
Most PARTIAL items are Oracle *abstract* tasks (no implementation in the source model either) and inbound adapters that need a Camunda inbound connector.

| Process | Auto % | Auto | Partial | Manual | Validation issues | Key conversion |
|---|---|---|---|---|---|---|
| [LOProcessActivationFromEmail](compare/LOProcessActivationFromEmail.png) | 25% | 1 | 3 | 0 | 0 | Inbound email adapter (composite `ReceiveLOEmail`) → message start event + hint to use the Camunda Email inbound connector. |
| [LOProcessActivationFromEvent](compare/LOProcessActivationFromEvent.png) | 67% | 2 | 1 | 0 | 0 | Oracle event (EDN) start → signal start event `LoanOriginationEvent`. |
| [LOProcessActivationFromQueue](compare/LOProcessActivationFromQueue.png) | 33% | 1 | 2 | 0 | 0 | Inbound JMS adapter (composite `ConsumeLoanRequest`) → message start event + hint for a JMS/Kafka inbound bridge. |
| [LOProcessAsService](compare/LOProcessAsService.png) | 76% | 13 | 4 | 0 | 0 | define_interface start → message start; 3 expanded sub-processes, XOR gateway with translated condition `=(1 = 2)` and default flow, terminate end. |
| [LOProcessHumanInitiation](compare/LOProcessHumanInitiation.png) | 50% | 2 | 2 | 0 | 0 | Oracle initiator task → user task (candidate group `LoanOfficer`) with a note to use a start form instead. |
| [LOProcessMultiEvent](compare/LOProcessMultiEvent.png) | 71% | 5 | 2 | 0 | 0 | Instantiating event-based gateway (not allowed in Zeebe) → two message start events. |
| [LOProcessOneRequestTwoResponse](compare/LOProcessOneRequestTwoResponse.png) | 50% | 3 | 3 | 0 | 0 | One SOAP operation, two reply end events (Approved / NotApproved) → XOR gateway with default flow; callers use "create instance with result". |
| [LOProcessSchedule](compare/LOProcessSchedule.png) | 50% | 2 | 2 | 0 | 0 | Oracle Schedule (daily 22:28) → cron `0 28 22 * * *`; timeCycle `PT2M` → `R/PT2M` (active window flagged). |
| [LOProcessSendReceive](compare/LOProcessSendReceive.png) | 60% | 3 | 2 | 0 | 0 | None start + instantiating receive task → merged into one message start event. |

## Regenerating

Images are produced from a scratch clone of the Packt repo (outside this project):

```bash
java -jar migrator-cli/target/oracle2c8.jar convert <packt>/Chapter03 -o camunda8-output/chapter03
```
then render: Oracle originals via the GraphicsAttributes renderer, Camunda output via bpmn-js in headless Chromium.
