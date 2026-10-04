/* Oracle BPM to Camunda 8 Migrator - local web UI. Plain JavaScript, no build step. */
'use strict';

const $ = (sel, el = document) => el.querySelector(sel);
const $$ = (sel, el = document) => Array.from(el.querySelectorAll(sel));
const state = { ws: null, status: null, result: null, selected: null, saveTimer: null, pending: {} };

// ---------------------------------------------------------------- helpers

async function api(method, path, body, raw) {
  const opts = { method, headers: { 'X-Requested-With': 'oracle2c8' } };
  if (body !== undefined) {
    if (raw) {
      opts.body = body;
    } else {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(body);
    }
  }
  let res;
  try {
    res = await fetch('api/' + path, opts);
  } catch (e) {
    throw new Error('The migrator server is not reachable. Is "oracle2c8 serve" still running?');
  }
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (e) { data = text; }
  if (!res.ok) {
    const err = new Error((data && data.error) || ('HTTP ' + res.status));
    err.status = res.status;
    err.kind = data && data.kind;
    throw err;
  }
  return data;
}

function wsPath(p) { return 'workspaces/' + state.ws + '/' + p; }

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

let toastTimer;
function toast(msg, bad) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.toggle('bad', !!bad);
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, bad ? 9000 : 4000);
}

function busy(on, text) {
  $('#busy').hidden = !on;
  if (text) $('#busyText').textContent = text;
}

async function guarded(fn, busyText) {
  if (busyText) busy(true, busyText);
  try {
    return await fn();
  } catch (e) {
    if (e.status === 404 && /session/.test(e.message)) {
      await newWorkspace();
    }
    toast(e.message, true);
    return undefined;
  } finally {
    if (busyText) busy(false);
  }
}

// ---------------------------------------------------------------- tabs

function showTab(name) {
  $$('.tab').forEach(t => {
    const on = t.dataset.tab === name;
    t.classList.toggle('active', on);
    t.setAttribute('aria-selected', String(on));
  });
  $$('.tabpanel').forEach(p => { p.hidden = p.id !== 'tab-' + name; });
  if (name === 'kb') loadKnowledgeBase();
}

function showSub(name) {
  $$('.subtab').forEach(t => t.classList.toggle('active', t.dataset.sub === name));
  $$('[data-subpanel]').forEach(p => { p.hidden = p.dataset.subpanel !== name; });
}

// ---------------------------------------------------------------- start-up

async function init() {
  $$('.tab').forEach(t => t.addEventListener('click', () => showTab(t.dataset.tab)));
  $$('.subtab').forEach(t => t.addEventListener('click', () => showSub(t.dataset.sub)));
  setupDrop();
  $('#btnConvert').addEventListener('click', () => run('convert'));
  $('#btnAnalyze').addEventListener('click', () => run('analyze'));
  $('#kbSaveForm').addEventListener('submit', saveKnowledge);
  $('#pushForm').addEventListener('submit', push);
  $('#closeDetail').addEventListener('click', closeDetail);
  $('#kbRefresh').addEventListener('click', loadKnowledgeBase);
  $('#wmTest').addEventListener('click', testWebModeler);
  $$('[data-clear]').forEach(b => b.addEventListener('click', () => clearArea(b.dataset.clear)));
  $('#pushFolder').value = 'Migration ' + new Date().toISOString().slice(0, 10);

  await guarded(async () => {
    state.status = await api('GET', 'status');
    renderStatus();
  });
  const saved = sessionStorage.getItem('oracle2c8.ws');
  if (saved) {
    try {
      state.ws = saved;
      const d = await api('GET', wsPath(''));
      renderWorkspace(d);
      if (d.hasResult) {
        state.result = await api('GET', wsPath('result'));
        renderResult(state.result);
      }
    } catch (e) {
      await newWorkspace();
    }
  } else {
    await newWorkspace();
  }
  loadKnowledgeBase(true);
}

async function newWorkspace() {
  const d = await api('POST', 'workspaces', {});
  state.ws = d.id;
  sessionStorage.setItem('oracle2c8.ws', d.id);
  state.result = null;
  renderWorkspace(d);
  $('#results').hidden = true;
  closeDetail();
}

function renderStatus() {
  const s = state.status;
  $('#version').textContent = 'Version ' + s.version + ' · runs locally · your files stay on this machine';
  $('#optVersion').value = s.defaultPlatformVersion;
  if (!$('#optVersion').value) {
    const o = document.createElement('option');
    o.textContent = s.defaultPlatformVersion;
    $('#optVersion').appendChild(o);
    $('#optVersion').value = s.defaultPlatformVersion;
  }
  $('#optUserTasks').value = s.defaultUserTasks;
  $('#kbDir').textContent = s.knowledgeBaseDir;
  $('#serverInfo').innerHTML = kv({
    'Settings file': s.config,
    'Knowledge-base folder': s.knowledgeBaseDir,
    'Upload limit': s.maxUploadMb + ' MB, ' + s.maxFiles + ' files per session',
    'Defaults': 'Camunda ' + s.defaultPlatformVersion + ', ' + s.defaultUserTasks + ' user tasks'
  });
  const wm = s.webModeler || {};
  const conf = !!wm.configured;
  const pill = $('#wmState');
  pill.textContent = conf ? 'configured: ' + wm.mode : 'not configured';
  pill.className = 'pill ' + (conf ? 'ok' : '');
  $('#wmInfo').innerHTML = wm.error ? kv({ 'Problem': wm.error }) : kv({
    'Mode': wm.mode === 'saas' ? 'SaaS' : 'Self-Managed',
    'Configured': conf ? 'yes' : 'no: set CAMUNDA_WEBMODELER_CLIENT_ID and CAMUNDA_WEBMODELER_CLIENT_SECRET',
    'API URL': wm.apiUrl,
    'Token URL': wm.tokenUrl,
    'Client id': wm.clientId || '(not set)',
    'Target project': wm.project
  });
}

function kv(obj) {
  return Object.entries(obj).map(([k, v]) => '<dt>' + esc(k) + '</dt><dd>' + esc(v) + '</dd>').join('');
}

// ---------------------------------------------------------------- uploads

function setupDrop() {
  $$('.drop').forEach(zone => {
    const area = zone.dataset.area;
    zone.addEventListener('dragover', e => { e.preventDefault(); zone.classList.add('over'); });
    zone.addEventListener('dragleave', () => zone.classList.remove('over'));
    zone.addEventListener('drop', async e => {
      e.preventDefault();
      zone.classList.remove('over');
      const files = await filesFromDrop(e.dataTransfer);
      upload(area, files);
    });
  });
  $$('input[data-pick]').forEach(inp => inp.addEventListener('change', () => {
    const files = Array.from(inp.files).map(f => ({ file: f, path: f.webkitRelativePath || f.name }));
    upload(inp.dataset.pick, files);
    inp.value = '';
  }));
}

/** Files and whole folders from a drag-and-drop, with their relative paths. */
async function filesFromDrop(dt) {
  const out = [];
  const entries = Array.from(dt.items || []).map(i => i.webkitGetAsEntry && i.webkitGetAsEntry()).filter(Boolean);
  if (!entries.length) {
    return Array.from(dt.files).map(f => ({ file: f, path: f.name }));
  }
  async function walk(entry, prefix) {
    if (entry.isFile) {
      const file = await new Promise((res, rej) => entry.file(res, rej));
      out.push({ file, path: prefix + file.name });
    } else if (entry.isDirectory) {
      const reader = entry.createReader();
      let batch;
      do {
        batch = await new Promise((res, rej) => reader.readEntries(res, rej));
        for (const child of batch) await walk(child, prefix + entry.name + '/');
      } while (batch.length);
    }
  }
  for (const e of entries) await walk(e, '');
  return out;
}

async function upload(area, files) {
  files = files.filter(f => !/(^|\/)\./.test(f.path) && !/(^|\/)(target|deploy|SCA-INF|classes)\//.test(f.path));
  if (!files.length) {
    toast('No files to upload (hidden files and build folders are skipped).', true);
    return;
  }
  let done = 0;
  busy(true, 'Uploading 0 of ' + files.length + ' file(s) ...');
  try {
    const queue = files.slice();
    const worker = async () => {
      while (queue.length) {
        const f = queue.shift();
        await api('PUT', wsPath('files/' + area + '?path=' + encodeURIComponent(f.path)), f.file, true);
        done++;
        $('#busyText').textContent = 'Uploading ' + done + ' of ' + files.length + ' file(s) ...';
      }
    };
    await Promise.all([worker(), worker(), worker(), worker()]);
    toast(files.length + ' file(s) uploaded.');
  } catch (e) {
    toast(e.message + (done ? ' (' + done + ' file(s) were uploaded before the error)' : ''), true);
  } finally {
    busy(false);
    const d = await guarded(() => api('GET', wsPath('')));
    if (d) renderWorkspace(d);
  }
}

async function clearArea(area) {
  const d = await guarded(() => api('DELETE', wsPath('files/' + area)));
  if (d) {
    renderWorkspace(d);
    if (area === 'migrate') {
      state.result = null;
      $('#results').hidden = true;
      closeDetail();
    }
  }
}

function renderWorkspace(d) {
  renderList('migrate', d.migrate);
  renderList('kb', d.kb);
  $('#btnConvert').disabled = !d.migrate.length;
  $('#btnAnalyze').disabled = !d.migrate.length;
  $('#kbSaveForm').hidden = !d.kb.length;
}

function renderList(area, files) {
  const el = $('#list-' + area);
  $('[data-clear="' + area + '"]').hidden = !files.length;
  if (!files.length) {
    el.innerHTML = '';
    return;
  }
  const bpmn = files.filter(f => /\.bpmn2?$/i.test(f)).length;
  const archives = files.filter(f => /\.(zip|sar|jar)$/i.test(f)).length;
  const composites = files.filter(f => /(^|\/)composite\.xml$/i.test(f)).length;
  const parts = [bpmn + ' BPMN file(s)'];
  if (composites) parts.push(composites + ' composite.xml');
  if (archives) parts.push(archives + ' archive(s)');
  el.innerHTML = '<details><summary>' + files.length + ' file(s) uploaded: ' + esc(parts.join(', ')) + '</summary><ul>'
    + files.slice(0, 500).map(f => '<li>' + esc(f) + '</li>').join('')
    + (files.length > 500 ? '<li>... and ' + (files.length - 500) + ' more</li>' : '') + '</ul></details>';
}

// ---------------------------------------------------------------- convert / analyze

function options() {
  return {
    userTasks: $('#optUserTasks').value,
    platformVersion: $('#optVersion').value,
    useKnowledgeBase: $('#optKb').checked
  };
}

async function run(kind) {
  const r = await guarded(() => api('POST', wsPath(kind), options()),
    kind === 'analyze' ? 'Converting and estimating effort ...' : 'Converting to Camunda 8 ...');
  if (!r) return;
  state.result = r;
  renderResult(r);
  closeDetail();
  $('#results').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function renderResult(r) {
  $('#results').hidden = false;
  const s = r.summary;
  const files = r.files.filter(f => f.status !== 'FAILED');
  const avg = files.length ? Math.round(files.reduce((a, f) => a + f.automationPercent, 0) / files.length) : 0;
  const tiles = [
    ['', s.files, 'process file(s)'],
    ['ok', s.converted, 'converted cleanly'],
    [s.convertedWithIssues ? 'warn' : '', s.convertedWithIssues, 'with validation issues'],
    [s.failed ? 'bad' : '', s.failed, 'failed'],
    ['', avg + '%', 'average automation'],
    ['', s.knowledgeBasePairs ? s.knowledgeBaseRules : '-', s.knowledgeBasePairs
      ? 'rules from ' + s.knowledgeBasePairs + ' finished migration(s)' : 'no knowledge base used']
  ];
  $('#tiles').innerHTML = tiles.map(([c, v, l]) => '<div class="tile ' + c + '"><div class="v">' + esc(v)
    + '</div><div class="l">' + esc(l) + '</div></div>').join('');
  const notes = [];
  if (s.composites) notes.push(s.composites + ' composite.xml file(s) used to configure service calls.');
  (r.skipped || []).forEach(x => notes.push('Skipped ' + x));
  (r.warnings || []).forEach(x => notes.push('Warning: ' + x));
  $('#runNotes').hidden = !notes.length;
  $('#runNotes').innerHTML = '<ul>' + notes.map(n => '<li>' + esc(n) + '</li>').join('') + '</ul>';

  $('#dlZip').href = 'api/' + wsPath('download.zip');
  $('#dlReport').href = 'api/' + wsPath('report.md');
  $('#dlChecklist').href = 'api/' + wsPath('checklist.md');
  $('#dlAnalysis').href = 'api/' + wsPath('analysis.md');
  renderAnalysis(r.analysis);

  $('#fileRows').innerHTML = r.files.map((f, i) => {
    const badge = f.status === 'CONVERTED' ? '<span class="badge ok">Converted</span>'
      : f.status === 'FAILED' ? '<span class="badge bad">Failed</span>'
        : '<span class="badge warn">' + f.validationIssues.length + ' issue(s)</span>';
    const done = f.review.filter(x => x.done).length;
    const review = f.status === 'FAILED' ? '' : f.review.length ? done + ' / ' + f.review.length : 'nothing to review';
    const auto = f.status === 'FAILED' ? '<span class="danger">' + esc(f.failure) + '</span>'
      : '<div class="bar"><div class="track"><div class="fill" style="width:' + f.automationPercent + '%"></div></div>'
        + f.automationPercent + '%</div>';
    const procs = (f.processes || []).filter(Boolean).join(', ');
    return '<tr class="clickable" data-i="' + i + '" tabindex="0"><td class="path">' + esc(f.path)
      + (procs ? '<small>' + esc(procs) + '</small>' : '') + '</td><td>' + badge + '</td><td' + (f.status === 'FAILED' ? ' colspan="4"' : '')
      + '>' + auto + '</td>' + (f.status === 'FAILED' ? '' : '<td class="num">' + f.auto + '</td><td class="num">' + f.partial
        + '</td><td class="num">' + f.manual + '</td>') + '<td>' + review + '</td></tr>';
  }).join('');
  $$('#fileRows tr').forEach(tr => {
    const open = () => openDetail(Number(tr.dataset.i));
    tr.addEventListener('click', open);
    tr.addEventListener('keydown', e => { if (e.key === 'Enter') open(); });
  });
}

function renderAnalysis(a) {
  $('#analysisCard').hidden = !a;
  if (!a) return;
  const labels = {
    'form': 'Camunda forms to build', 'job-worker': 'Job workers to implement', 'inbound-trigger': 'Inbound triggers to set up',
    'correlation': 'Correlation keys to define', 'undefined-task': 'Undefined tasks to decide',
    'expression': 'Expressions to rewrite', 'unsupported': 'Constructs to remodel', 'secret': 'Secrets to create',
    'review': 'Other items to review', 'failed-file': 'Files to migrate by hand'
  };
  const rows = Object.entries(a.workByKind || {}).map(([k, n]) => '<tr><td>' + esc(labels[k] || k) + '</td><td class="num">' + n
    + '</td><td class="num">' + a.weights[k] + '</td><td class="num">' + fmt(n * a.weights[k]) + '</td></tr>').join('');
  const converted = a.processes.filter(p => p.status !== 'FAILED').length;
  $('#analysis').innerHTML = '<div class="tiles"><div class="tile"><div class="v">' + fmt(a.totalHours)
    + ' h</div><div class="l">rough effort</div></div><div class="tile"><div class="v">' + a.averageAutomationPercent
    + '%</div><div class="l">average automation</div></div></div>'
    + '<div class="table-wrap"><table><thead><tr><th>Kind of work</th><th class="num">Items</th><th class="num">Hours each</th><th class="num">Hours</th></tr></thead><tbody>'
    + rows + '<tr><td>Testing and deployment, per converted process</td><td class="num">' + converted + '</td><td class="num">'
    + a.weights['per-process'] + '</td><td class="num">' + fmt(converted * a.weights['per-process']) + '</td></tr></tbody></table></div>'
    + '<p class="hint">A planning starting point, not a quote. Adjust the hour weights with <code>analyze.hours.*</code> in application.properties.</p>';
}

function fmt(n) { return Math.round(n * 10) / 10; }

// ---------------------------------------------------------------- detail

async function openDetail(i) {
  const f = state.result.files[i];
  state.selected = i;
  $$('#fileRows tr').forEach(tr => tr.classList.toggle('selected', Number(tr.dataset.i) === i));
  $('#detail').hidden = false;
  $('#detailTitle').textContent = f.path;
  $('#dlModel').hidden = f.status === 'FAILED';
  $('#dlModel').href = 'api/' + wsPath('model?download=1&path=' + encodeURIComponent(f.path));
  $('#reviewCount').textContent = f.review.length || '';
  $('#issueCount').textContent = f.validationIssues.length || '';
  renderReview(f);
  renderIssues(f);
  showSub('compare');
  $('#detail').scrollIntoView({ behavior: 'smooth', block: 'start' });

  const oc = $('#oracleCanvas');
  oc.innerHTML = '<div class="empty">Loading ...</div>';
  try {
    const res = await fetch('api/' + wsPath('oracle-svg?path=' + encodeURIComponent(f.path)));
    oc.innerHTML = res.ok ? await res.text() : '<div class="empty">' + esc((await res.json()).error) + '</div>';
    const svg = oc.querySelector('svg');
    if (svg) {
      // fit the drawing into the panel; the viewBox keeps the proportions
      svg.removeAttribute('width');
      svg.removeAttribute('height');
      svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
    }
  } catch (e) {
    oc.innerHTML = '<div class="empty">The Oracle diagram could not be loaded.</div>';
  }
  const cc = $('#camundaCanvas');
  if (f.status === 'FAILED') {
    cc.innerHTML = '<div class="empty">Not converted: ' + esc(f.failure) + '</div>';
    return;
  }
  cc.innerHTML = '<div class="empty">Loading viewer ...</div>';
  try {
    const xml = await (await fetch('api/' + wsPath('model?path=' + encodeURIComponent(f.path)))).text();
    await showBpmn(cc, xml);
  } catch (e) {
    cc.innerHTML = '<div class="empty">The diagram viewer could not show this model: ' + esc(e.message)
      + '. Download the .bpmn file and open it in Camunda Modeler.</div>';
  }
}

function closeDetail() {
  $('#detail').hidden = true;
  state.selected = null;
  $$('#fileRows tr').forEach(tr => tr.classList.remove('selected'));
}

function renderIssues(f) {
  const el = $('#issueList');
  if (f.status === 'FAILED') {
    el.innerHTML = '<div class="result-box bad"><strong>Not converted.</strong> ' + esc(f.failure) + '</div>';
  } else if (!f.validationIssues.length) {
    el.innerHTML = '<div class="result-box ok">No validation issues: the model passed the Camunda 8 rules and the BPMN 2.0 schema check.</div>';
  } else {
    el.innerHTML = '<div class="result-box bad"><strong>Fix these before deploying:</strong><ul>'
      + f.validationIssues.map(x => '<li>' + esc(x) + '</li>').join('') + '</ul></div>';
  }
  if (f.secrets && f.secrets.length) {
    el.innerHTML += '<p class="hint">Secrets to create in Camunda: ' + f.secrets.map(s => '<code>' + esc(s) + '</code>').join(' ') + '</p>';
  }
}

function renderReview(f) {
  const ul = $('#reviewList');
  if (!f.review.length) {
    ul.innerHTML = '<li class="done"><span></span><span>Nothing left to review in this process.</span></li>';
    return;
  }
  ul.innerHTML = f.review.map((it, j) => '<li class="' + (it.done ? 'done' : '') + '" data-j="' + j + '">'
    + '<input type="checkbox" aria-label="Done" ' + (it.done ? 'checked' : '') + '>'
    + '<div><div class="head"><span class="badge ' + (it.level === 'MANUAL' ? 'bad' : 'warn') + '">' + it.level + '</span>'
    + esc(it.name || it.elementId) + ' <span class="hint">' + esc(it.type) + ' · ' + esc(it.source) + '</span></div>'
    + '<ul class="notes">' + it.notes.map(n => '<li>' + esc(n) + '</li>').join('') + '</ul></div>'
    + '<textarea placeholder="Comment (optional)">' + esc(it.comment) + '</textarea></li>').join('');
  $$('li[data-j]', ul).forEach(li => {
    const it = f.review[Number(li.dataset.j)];
    $('input', li).addEventListener('change', e => {
      it.done = e.target.checked;
      li.classList.toggle('done', it.done);
      queueSave(it);
      updateReviewCell();
    });
    $('textarea', li).addEventListener('input', e => { it.comment = e.target.value; queueSave(it); });
  });
}

function updateReviewCell() {
  const f = state.result.files[state.selected];
  const tr = $('#fileRows tr[data-i="' + state.selected + '"]');
  if (tr && f.review.length) tr.lastElementChild.textContent = f.review.filter(x => x.done).length + ' / ' + f.review.length;
}

function queueSave(it) {
  state.pending[it.key] = { done: it.done, comment: it.comment };
  clearTimeout(state.saveTimer);
  state.saveTimer = setTimeout(async () => {
    const items = state.pending;
    state.pending = {};
    await guarded(() => api('PUT', wsPath('checklist'), { items }));
  }, 500);
}

// ---------------------------------------------------------------- BPMN viewer

const CDN = 'https://unpkg.com/bpmn-js@17.11.1/dist/';
let viewerLib = null;

function loadScript(src, timeoutMs) {
  return new Promise((resolve, reject) => {
    const s = document.createElement('script');
    const t = setTimeout(() => reject(new Error('timeout')), timeoutMs);
    s.src = src;
    s.onload = () => { clearTimeout(t); resolve(); };
    s.onerror = () => { clearTimeout(t); reject(new Error('failed to load ' + src)); };
    document.head.appendChild(s);
  });
}

function loadCss(href) {
  const l = document.createElement('link');
  l.rel = 'stylesheet';
  l.href = href;
  document.head.appendChild(l);
}

/** Current bpmn-js from the CDN when online, else the copy bundled in the jar (offline use). */
async function bpmnLib() {
  if (viewerLib) return viewerLib;
  try {
    await loadScript(CDN + 'bpmn-navigated-viewer.production.min.js', 5000);
    loadCss(CDN + 'assets/diagram-js.css');
    loadCss(CDN + 'assets/bpmn-js.css');
    loadCss(CDN + 'assets/bpmn-font/css/bpmn-embedded.css');
    viewerLib = { BpmnJS: window.BpmnJS, modern: true };
  } catch (e) {
    await loadScript('vendor/bpmn-js/bpmn-navigated-viewer.min.js', 15000);
    loadCss('vendor/bpmn-js/assets/diagram-js.css');
    loadCss('vendor/bpmn-js/assets/bpmn-font/css/bpmn-embedded.css');
    viewerLib = { BpmnJS: window.BpmnJS, modern: false };
  }
  return viewerLib;
}

async function showBpmn(container, xml) {
  const lib = await bpmnLib();
  container.innerHTML = '';
  const viewer = new lib.BpmnJS({ container });
  if (lib.modern) {
    await viewer.importXML(xml);
  } else {
    await new Promise((resolve, reject) => viewer.importXML(xml, err => (err ? reject(err) : resolve())));
  }
  viewer.get('canvas').zoom('fit-viewport');
}

// ---------------------------------------------------------------- knowledge base

async function saveKnowledge(e) {
  e.preventDefault();
  const name = $('#kbName').value.trim();
  let r;
  try {
    busy(true, 'Saving to the knowledge base ...');
    r = await api('POST', wsPath('kb/save'), { name });
  } catch (err) {
    busy(false);
    if (err.status === 409 && confirm(err.message + '\n\nReplace the existing entry?')) {
      r = await guarded(() => api('POST', wsPath('kb/save'), { name, overwrite: true }), 'Saving ...');
    } else {
      toast(err.message, true);
    }
  } finally {
    busy(false);
  }
  if (r) {
    toast('Saved "' + r.saved + '" with ' + r.pairs + ' finished migration(s) to the knowledge base.');
    $('#kbName').value = '';
    loadKnowledgeBase(true);
  }
}

async function loadKnowledgeBase(quiet) {
  const kb = await guarded(() => api('GET', 'knowledge-base'));
  if (!kb) return;
  $('#kbCount').textContent = kb.pairs.length || '';
  if (quiet === true && $('#tab-kb').hidden) return;
  $('#kbDir').textContent = kb.dir;
  if (!kb.entries.length && !kb.pairs.length) {
    $('#kbBody').innerHTML = '<p>The knowledge base is empty. Upload finished migrations on the Migrate tab and choose '
      + '<em>Save to knowledge base</em>, or copy them into the folder above.</p>';
    return;
  }
  const rules = Object.entries(kb.rules).map(([k, n]) => esc(k.toLowerCase().replace('_', ' ')) + ': ' + n).join(' · ');
  $('#kbBody').innerHTML =
    '<h3>Entries</h3><div class="table-wrap"><table><thead><tr><th>Name</th><th class="num">Files</th><th></th></tr></thead><tbody>'
    + kb.entries.map(e => '<tr><td>' + esc(e.name) + '</td><td class="num">' + e.files
      + '</td><td><button class="link danger" data-del="' + esc(e.name) + '">Remove</button></td></tr>').join('')
    + '</tbody></table></div>'
    + '<h3>Finished migrations found (' + kb.pairs.length + ')</h3>'
    + (kb.pairs.length ? '<div class="table-wrap"><table><thead><tr><th>Oracle</th><th>Camunda 8</th><th>Matched by</th></tr></thead><tbody>'
      + kb.pairs.map(p => '<tr><td>' + esc(p.oracle) + '</td><td>' + esc(p.camunda) + '</td><td>' + esc(p.matchedBy) + '</td></tr>').join('')
      + '</tbody></table></div>' : '<p class="hint">No Oracle process could be paired with a Camunda model yet.</p>')
    + '<p><strong>Learned rules:</strong> ' + (rules || 'none') + '</p>'
    + (kb.notes.length ? '<div class="notice"><ul>' + kb.notes.map(n => '<li>' + esc(n) + '</li>').join('') + '</ul></div>' : '');
  $$('[data-del]').forEach(b => b.addEventListener('click', async () => {
    if (!confirm('Remove "' + b.dataset.del + '" from the knowledge base? Its files are deleted from the folder.')) return;
    const ok = await guarded(() => api('DELETE', 'knowledge-base/' + encodeURIComponent(b.dataset.del)));
    if (ok) {
      toast('Removed "' + b.dataset.del + '".');
      loadKnowledgeBase();
    }
  }));
}

// ---------------------------------------------------------------- Web Modeler

async function push(e) {
  e.preventDefault();
  const box = $('#pushResult');
  box.innerHTML = '';
  try {
    busy(true, 'Uploading to Camunda Web Modeler ...');
    const r = await api('POST', wsPath('webmodeler/push'), { folder: $('#pushFolder').value });
    box.innerHTML = '<div class="result-box ok"><strong>Uploaded to project "' + esc(r.project) + '", folder "' + esc(r.folder)
      + '".</strong><ul>' + (r.created.length ? '<li>Created: ' + esc(r.created.join(', ')) + '</li>' : '')
      + (r.updated.length ? '<li>Updated (new revision): ' + esc(r.updated.join(', ')) + '</li>' : '')
      + (r.skippedFailed ? '<li>' + r.skippedFailed + ' failed file(s) were not uploaded.</li>' : '')
      + '</ul><a href="' + esc(r.url) + '" target="_blank" rel="noopener">Open the project in Web Modeler</a></div>';
  } catch (err) {
    box.innerHTML = '<div class="result-box bad"><strong>Upload to Web Modeler failed.</strong> ' + esc(err.message) + '</div>';
  } finally {
    busy(false);
  }
}

async function testWebModeler() {
  const box = $('#wmTestResult');
  box.innerHTML = '';
  try {
    busy(true, 'Connecting to Camunda Web Modeler ...');
    const r = await api('GET', 'webmodeler/status');
    box.innerHTML = '<div class="result-box ok"><strong>Connected.</strong> Organization: ' + esc(r.organization || '-')
      + ', API ' + esc(r.apiVersion || '') + '. The client can create and update files.</div>';
  } catch (err) {
    box.innerHTML = '<div class="result-box bad"><strong>Connection failed.</strong> ' + esc(err.message) + '</div>';
  } finally {
    busy(false);
  }
}

init();
