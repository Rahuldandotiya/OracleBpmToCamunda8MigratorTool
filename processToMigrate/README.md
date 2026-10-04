# processToMigrate/

Drop what you want to convert to Camunda 8:

- Oracle BPM `.bpmn` files,
- whole JDeveloper projects (with `composite.xml`, so service calls are configured as connectors or workers),
- `.zip` / `.sar` archives of projects.

Then run `oracle2c8 migrate`. The result goes to `camunda8-output/`, which keeps the same folder
structure and adds `conversion-report.md`.

For an example, copy `samples/oracle-bpm-12c/loan-origination` into this folder and run `oracle2c8 migrate`.

Contents of this folder (except this README) are ignored by git, so client models are not
pushed by accident.
