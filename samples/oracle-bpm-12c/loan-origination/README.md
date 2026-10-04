# Sample: loan-origination (Oracle BPM 12c)

A real Oracle BPM Suite 12c SOA project used as the sample input for the migrator.
It contains nine BPMN processes that show the different ways an Oracle process can be
started and how it replies: as a web service, from a JMS queue, from an email, from a
business event, on a schedule, by a person (initiator task), with several start
events, with send/receive and with one request / two replies.

```
loan-origination/
└── LoanOrigination/SOA/
    ├── composite.xml          services, references and wires (read by the migrator)
    ├── processes/*.bpmn       the nine Oracle BPMN processes
    ├── Adapters/*.jca         inbound JMS and email adapter settings
    ├── WSDLs/, Schemas/       service interfaces and data types
    ├── HumanTasks/*.task      the human task definition
    └── Events/*.edl           the business event definition
```

Convert it from the repository root:

```bash
java -jar migrator-cli/target/oracle2c8.jar convert samples/oracle-bpm-12c/loan-origination -o camunda8-output
```

## Source and license

These files are taken unchanged (build output, ADF task-form project and deployment
archives left out) from the `LoanOriginationProcess` project in
[PacktPublishing/Oracle-BPM-Suite-12c-Modeling-Patterns](https://github.com/PacktPublishing/Oracle-BPM-Suite-12c-Modeling-Patterns)
(commit `b2c87e4`), the companion code of the book *Oracle BPM Suite 12c Modeling Patterns*
(Vivek Acharya, Packt Publishing).

They are distributed under the MIT License, Copyright (c) 2019 Packt. See [LICENSE](LICENSE).
They are not covered by this project's Apache 2.0 license.
