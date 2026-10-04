# verify-bpmn

Checks converted models the way Camunda Modeler does, in two steps:

1. **Import** with [bpmn-moddle](https://github.com/bpmn-io/bpmn-moddle) and the
   [Zeebe extension](https://github.com/camunda/zeebe-bpmn-moddle), the same parser Modeler uses.
   Any import warning means Modeler would drop or misread part of the model.
2. **Camunda's own lint rules** ([bpmnlint-plugin-camunda-compat](https://github.com/camunda/bpmnlint-plugin-camunda-compat)),
   the rules behind Modeler's problems panel, for the target Camunda version.

```bash
cd tools/verify-bpmn
npm install
node verify.mjs --platform 8.6 ../../camunda8-output
```

Exit code 0 when every file passes. CI runs it on the converted sample and test fixtures.
The migrator itself already checks every model against the BPMN 2.0 XML schema and its own
Camunda 8 rules; this tool adds the official Camunda checks on top.
