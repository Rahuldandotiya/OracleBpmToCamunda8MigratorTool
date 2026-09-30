# knowledge-base/

Drop **finished migrations** here: Oracle processes together with the Camunda 8 models your team
completed for them. `oracle2c8 migrate` learns from them and applies the same decisions to new
processes (service call settings, forms, candidate groups, variable names, job type naming ...).

Anything goes, in any folder structure:

- loose Oracle `.bpmn` files,
- whole JDeveloper projects (with `composite.xml`, WSDL/WADL, `.jca`, config plans),
- `.zip` / `.sar` archives of projects,
- the finished Camunda 8 `.bpmn` files (recognised by the Camunda/zeebe namespace).

Oracle and Camunda files are paired automatically: same process id, else same file name, else
mostly the same element ids, else the same process name. Keeping each pair in its own sub-folder
helps, for example:

```
knowledge-base/
  claims-fnol/
    oracle/FNOLProcess.bpmn
    camunda/FNOLProcess.bpmn
  claim-intake/
    ClaimIntake/SOA/composite.xml ...      (whole Oracle project)
    camunda/ClaimIntakeProcess.bpmn
```

See `samples/demo/knowledge-base` for a complete example.

Contents of this folder (except this README) are ignored by git, so client models are not
pushed by accident.
