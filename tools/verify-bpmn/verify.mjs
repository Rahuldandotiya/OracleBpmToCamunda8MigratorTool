#!/usr/bin/env node
/*
 * Verifies Camunda 8 BPMN files the way Camunda Modeler does:
 *   1. import with bpmn-moddle + the Zeebe extension (what Modeler uses to read a file);
 *      any import warning means Modeler would drop or misread part of the model;
 *   2. Camunda's own lint rules (bpmnlint-plugin-camunda-compat), the rules behind
 *      Modeler's problems panel, for the target Camunda version.
 *
 * Usage: node verify.mjs [--platform 8.6] [--skip-rules feel,feel-compatibility] <files or folders...>
 * Exit code 0 when every file imports cleanly and has no lint errors, 1 otherwise.
 */
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { BpmnModdle } = await import('bpmn-moddle');
const { Linter } = require('bpmnlint');
const compat = require('bpmnlint-plugin-camunda-compat');
const zeebe = require('zeebe-bpmn-moddle/resources/zeebe.json');

const args = process.argv.slice(2);
let platform = '8.6';
let skip = [];
const inputs = [];
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--platform') platform = args[++i];
  else if (args[i] === '--skip-rules') skip = args[++i].split(',');
  else inputs.push(args[i]);
}

const configName = 'camunda-cloud-' + platform.split('.').slice(0, 2).join('-');
if (!compat.configs[configName]) {
  console.error(`Unknown Camunda version ${platform} (no lint config ${configName})`);
  process.exit(2);
}
const rules = Object.fromEntries(Object.entries(compat.configs[configName].rules)
  .filter(([name]) => !skip.includes(name))
  .map(([name, value]) => [name.includes('/') ? name : 'camunda-compat/' + name, value]));

const pluginDir = path.dirname(require.resolve('bpmnlint-plugin-camunda-compat'));
const bpmnlintDir = path.dirname(path.dirname(require.resolve('bpmnlint')));
const linter = new Linter({
  config: { rules },
  resolver: {
    resolveRule: (pkg, name) => pkg === 'bpmnlint'
      ? require(path.join(bpmnlintDir, 'rules', name))
      : require(path.join(pluginDir, compat.rules[name])),
    resolveConfig: () => { throw new Error('no nested configs'); }
  }
});

const files = [];
const walk = (p) => {
  const st = fs.statSync(p);
  if (st.isDirectory()) fs.readdirSync(p).forEach(n => walk(path.join(p, n)));
  else if (p.endsWith('.bpmn')) files.push(p);
};
inputs.forEach(walk);
if (!files.length) {
  console.error('No .bpmn files found in ' + inputs.join(', '));
  process.exit(2);
}

const moddle = new BpmnModdle({ zeebe });
let failed = 0;
for (const file of files) {
  const problems = [];
  let root;
  try {
    const result = await moddle.fromXML(fs.readFileSync(file, 'utf8'));
    root = result.rootElement;
    result.warnings.forEach(w => problems.push('import: ' + w.message));
  } catch (e) {
    problems.push('import failed: ' + e.message);
  }
  if (root) {
    const reports = await linter.lint(root);
    for (const [rule, list] of Object.entries(reports)) {
      for (const r of list) {
        if (r.category === 'error' || r.category === 'rule-error') {
          problems.push(`${rule}: ${r.message}${r.id ? ' (' + r.id + ')' : ''}`);
        }
      }
    }
  }
  if (problems.length) {
    failed++;
    console.log('FAIL ' + file);
    problems.forEach(p => console.log('     ' + p));
  } else {
    console.log('OK   ' + file);
  }
}
console.log(`${files.length - failed}/${files.length} file(s) passed (Camunda ${platform})`);
process.exit(failed ? 1 : 0);
