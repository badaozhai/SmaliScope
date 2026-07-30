'use strict';

// ── 全局状态 ──────────────────────────────────────────────────────────────
const S = {
  serial: null,
  pkg: null,
  cls: null, method: null, sig: null,
  view: null,          // 当前方法的 MethodView
  state: null,         // 内核推来的 DebugState
  bps: [],
  templates: [],       // 预设断点模板（随应用变化）
  timeline: [],
  replaying: null,     // 时间线回放时选中的快照
  prevValues: {},      // 上一步的寄存器值，用来显示「旧值残影」
};

const $ = (s) => document.querySelector(s);
const $$ = (s) => Array.from(document.querySelectorAll(s));
const el = (tag, cls, text) => {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
};

/** 可点击的列表项：带上 role 与 tabindex，键盘和读屏也能用。 */
const clickable = (e, label, onActivate) => {
  e.setAttribute('role', 'button');
  e.setAttribute('tabindex', '0');
  if (label) e.setAttribute('aria-label', label);
  e.onclick = onActivate;
  e.onkeydown = (ev) => {
    if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); onActivate(ev); }
  };
  return e;
};

async function request(method, path, params) {
  const url = new URL(path, location.origin);
  for (const [k, v] of Object.entries(params || {})) {
    if (v !== undefined && v !== null) url.searchParams.set(k, v);
  }
  const r = await fetch(url, { method });
  const text = await r.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (_) { data = null; }
  if (!r.ok) throw new Error((data && data.error) || `请求失败 (${r.status})`);
  return data;
}

/** 改变状态的操作。 */
const api = (path, params) => request('POST', path, params);
/** 幂等查询。 */
const get = (path, params) => request('GET', path, params);

function hint(msg, kind) {
  const bar = $('#hintBar');
  if (!msg) { bar.classList.add('hidden'); return; }
  bar.textContent = msg;
  bar.classList.remove('hidden');
  bar.style.background = kind === 'error' ? '#3a1e1e' : '#2a2416';
  bar.style.color = kind === 'error' ? '#ffb4b4' : '#f0d79a';
}

// ── 启动 ─────────────────────────────────────────────────────────────────
async function boot() {
  try {
    const b = await get('/api/bootstrap');
    if (!b.ok) { $('#envInfo').textContent = b.message; hint(b.message, 'error'); return; }
    S.serial = b.serial;
    $('#envInfo').textContent =
      `${b.serial} · Android SDK ${b.env.sdk}${b.env.emulator ? ' · 模拟器' : ''} · 接入路径 ${b.env.path}`;
    if (!b.env.roDebuggable) hint(b.env.summary);

    const sel = $('#appSelect');
    sel.innerHTML = '';
    const sorted = b.apps.slice().sort((a, c) => (c.debuggable - a.debuggable) || a.pkg.localeCompare(c.pkg));
    for (const a of sorted) {
      const o = el('option', null, a.debuggable ? `● ${a.pkg}（可调试）` : a.pkg);
      o.value = a.pkg;
      sel.appendChild(o);
    }
    if (!sorted.length) sel.appendChild(el('option', null, '设备上没有第三方应用'));

    // 没配大模型 API key 时，这个标签页整个不出现。
    $('#tabExplain').classList.toggle('hidden', !b.llm);

    // 刷新页面时恢复现场：状态本来只从 SSE 推来，新页面在下一次事件之前是空的。
    if (b.session && b.session.pkg) await restore(b.session.pkg);
  } catch (e) {
    hint('无法连接设备：' + e.message, 'error');
  }
}

async function restore(pkg) {
  S.pkg = pkg;
  const sel = $('#appSelect');
  for (const o of sel.options) if (o.value === pkg) sel.value = pkg;
  await loadClasses();
  await loadTemplates();
  await refreshBps();
  const st = await get('/api/state').catch(() => null);
  // applyState 在挂起时会自动把代码视图切到当前所在的方法。
  if (st) applyState(st);
}

async function loadApp() {
  const pkg = $('#appSelect').value;
  if (!pkg) return;
  hint('正在获取并解析 APK，首次可能需要几秒…');
  try {
    const r = await api('/api/session', { pkg });
    S.pkg = pkg;
    hint(`已载入 ${pkg}（${r.classCount} 个类）。选一个类和方法，在指令左侧点圆点下断点，然后点「开始调试」。`);
    await loadClasses();
    await loadTemplates();
  } catch (e) {
    hint('载入失败：' + e.message, 'error');
  }
}

async function loadTemplates() {
  S.templates = await get('/api/templates').catch(() => []);
  renderBreakpoints();
}

async function loadClasses() {
  const list = await get('/api/classes', { filter: $('#classFilter').value });
  const box = $('#classList');
  box.innerHTML = '';
  if (!list.length) { box.appendChild(el('div', 'empty', '没有匹配的类')); return; }
  for (const c of list) {
    const item = el('div', 'item', c);
    item.title = c;
    clickable(item, c, () => selectClass(c, item));
    box.appendChild(item);
  }
}

/** Java 视图：看懂逻辑用，断点仍下在 smali 侧。首次调用会触发 jadx 加载，稍慢。 */
async function loadJava(cls) {
  const panel = $('#panel-java');
  panel.innerHTML = '';
  panel.appendChild(el('div', 'empty', `正在反编译 ${cls}…`));
  try {
    const r = await get('/api/java', { class: cls });
    panel.innerHTML = '';
    if (r && r.ok && r.code) {
      const pre = el('pre', null, r.code);
      pre.id = 'javaCode';
      panel.appendChild(pre);
    } else {
      panel.appendChild(el('div', 'empty', (r && r.message) || '无法反编译该类。'));
    }
  } catch (e) {
    panel.innerHTML = '';
    panel.appendChild(el('div', 'empty', '反编译失败：' + e.message));
  }
}

async function selectClass(cls, node) {
  highlightClass(cls, node);
  S.cls = cls;
  loadJava(cls);
  await loadMethods(cls);
}

function highlightClass(cls, node) {
  const target = node || $$('#classList .item').find(n => n.textContent === cls);
  $$('#classList .item').forEach(n => n.classList.remove('active'));
  if (target) {
    target.classList.add('active');
    target.scrollIntoView({ block: 'nearest' });
  }
}

async function loadMethods(cls) {
  const ms = await get('/api/methods', { class: cls });
  const box = $('#methodList');
  box.innerHTML = '';
  for (const m of ms) {
    const item = el('div', 'item');
    item.appendChild(document.createTextNode(m.name));
    item.appendChild(el('span', 'muted', `${m.signature} · ${m.insnCount} 条`));
    item.dataset.key = m.name + m.signature;
    clickable(item, `${m.name}${m.signature}`, () => selectMethod(m.name, m.signature, item));
    box.appendChild(item);
  }
}

async function selectMethod(name, sig, node) {
  highlightMethod(name, sig, node);
  S.method = name; S.sig = sig;
  await refreshMethodView();
}

function highlightMethod(name, sig, node) {
  const target = node || $$('#methodList .item').find(n => n.dataset.key === name + sig);
  $$('#methodList .item').forEach(n => n.classList.remove('active'));
  if (target) {
    target.classList.add('active');
    target.scrollIntoView({ block: 'nearest' });
  }
}

/**
 * 跳到某个类的某个方法，并把左侧两栏的选中状态同步过去。
 * 命中断点落在别的方法、或刷新页面恢复现场时都走这里——
 * 只换中间的代码而不动左栏，会让人不知道自己现在在哪。
 */
async function navigateTo(fqcn, method, sig) {
  const classChanged = S.cls !== fqcn;
  S.cls = fqcn; S.method = method; S.sig = sig;
  if (classChanged) {
    highlightClass(fqcn, null);
    loadJava(fqcn);
    await loadMethods(fqcn);
  }
  highlightMethod(method, sig, null);
  await refreshMethodView();
}

async function refreshMethodView() {
  if (!S.cls || !S.method) return;
  const pc = currentPcInThisMethod();
  const v = await get('/api/method', { class: S.cls, method: S.method, sig: S.sig, pc });
  S.view = v;
  if (!v) return;
  $('#methodTitle').textContent = `${v.fqcn}.${v.method}${v.signature}`;
  $('#regNote').textContent = v.analysisWarning ? '⚠ 类型推导不完整' : '';
  renderCode();
  renderCfg();
}

// ── smali 代码视图 ────────────────────────────────────────────────────────
function regNameToIndex(view) {
  const map = {};
  (view.registerNames || []).forEach((n, i) => { map[n] = i; });
  return map;
}

/** 把指令文本里的寄存器 / 引用 / 字面量分别着色，读写用不同颜色。 */
function colorize(text, insn, nameMap) {
  const reads = new Set(insn.reads), writes = new Set(insn.writes);
  const frag = document.createDocumentFragment();
  const re = /\b[vp]\d+\b|L[\w$/]+;->[\w$<>]+|"[^"]*"|-?0x[0-9a-fA-F]+|(?<=, )-?\d+\b/g;
  let last = 0, m;
  while ((m = re.exec(text)) !== null) {
    if (m.index > last) frag.appendChild(document.createTextNode(text.slice(last, m.index)));
    const tok = m[0];
    let cls = null;
    if (/^[vp]\d+$/.test(tok)) {
      const idx = nameMap[tok];
      if (writes.has(idx)) cls = 'tok-write';
      else if (reads.has(idx)) cls = 'tok-read';
    } else if (tok.startsWith('L') || tok.startsWith('"')) cls = 'tok-ref';
    else cls = 'tok-lit';
    frag.appendChild(cls ? el('span', cls, tok) : document.createTextNode(tok));
    last = m.index + tok.length;
  }
  if (last < text.length) frag.appendChild(document.createTextNode(text.slice(last)));
  return frag;
}

function renderCode() {
  const v = S.view;
  const box = $('#smali');
  box.innerHTML = '';
  const nameMap = regNameToIndex(v);
  const curPc = currentPcInThisMethod();
  const bpPcs = new Map(
    S.bps.filter(b => b.fqcn === v.fqcn && b.method === v.method && b.signature === v.signature)
      .map(b => [b.dexPc, b])
  );

  for (const insn of v.instructions) {
    const row = el('div', 'row');
    row.dataset.pc = insn.dexPc;

    const bp = el('div', 'bp', bpPcs.has(insn.dexPc) ? '●' : '○');
    bp.title = '点击设置 / 取消断点';
    clickable(bp, `在 dex_pc ${insn.dexPc} 设置或取消断点`, (e) => {
      e.stopPropagation();
      toggleBp(insn.dexPc);
    });
    row.appendChild(bp);

    if (bpPcs.has(insn.dexPc)) {
      row.classList.add(bpPcs.get(insn.dexPc).state === 'pending' ? 'pendingbp' : 'hasbp');
    }

    row.appendChild(el('div', 'pc', String(insn.dexPc)));
    row.appendChild(el('div', 'arrow', insn.dexPc === curPc ? '▶' : ''));

    const txt = el('div', 'txt');
    txt.appendChild(colorize(insn.text, insn, nameMap));
    row.appendChild(txt);

    if (insn.dexPc === curPc) row.classList.add('current');

    if (insn.doc) {
      row.onmouseenter = (e) => showTip(e, insn.text + '\n\n' + insn.doc);
      row.onmousemove = moveTip;
      row.onmouseleave = hideTip;
    }
    box.appendChild(row);
  }

  const cur = box.querySelector('.row.current');
  if (cur) cur.scrollIntoView({ block: 'center', behavior: 'smooth' });
}

function currentPcInThisMethod() {
  if (S.replaying) {
    const r = S.replaying;
    if (S.view && r.fqcn === S.view.fqcn && r.method === S.view.method) return r.dexPc;
    return null;
  }
  const st = S.state;
  if (!st || st.status !== 'suspended' || !st.frames.length) return null;
  const f = st.frames[0];
  if (!S.view) return f.dexPc;
  if (f.fqcn === S.view.fqcn && f.method === S.view.method && f.signature === S.view.signature) return f.dexPc;
  return null;
}

// ── 数据流条：当前指令读了谁、写到哪 ──────────────────────────────────────
function renderDataflow(insn, regs) {
  const box = $('#dataflow');
  if (!insn || (!insn.reads.length && !insn.writes.length)) {
    box.classList.add('hidden');
    return;
  }
  const valueOf = (i) => {
    const r = (regs || []).find(x => x.reg === i);
    return r && r.readable ? r.value : null;
  };
  const chip = (i, cls) => {
    const span = el('span');
    span.appendChild(el('span', cls, nameOfReg(i)));
    const v = valueOf(i);
    if (v != null) span.appendChild(el('span', 'v', `=${v}`));
    return span;
  };

  box.innerHTML = '';
  box.classList.remove('hidden');
  box.appendChild(el('span', 'op', insn.opcode));
  insn.reads.forEach((r, i) => {
    if (i) box.appendChild(document.createTextNode('、'));
    box.appendChild(chip(r, 'r'));
  });
  if (insn.writes.length) {
    box.appendChild(el('span', 'to', insn.reads.length ? '──▶' : '▶'));
    insn.writes.forEach((w, i) => {
      if (i) box.appendChild(document.createTextNode('、'));
      box.appendChild(chip(w, 'w'));
    });
  } else if (insn.isInvoke) {
    box.appendChild(el('span', 'to', '──▶'));
    box.appendChild(el('span', 'v', '返回值需下一条 move-result 取回'));
  }
}

function nameOfReg(i) {
  const names = (S.view && S.view.registerNames) || [];
  return names[i] || ('v' + i);
}

// ── 寄存器面板 ────────────────────────────────────────────────────────────
function renderRegisters() {
  const box = $('#registers');
  const regs = S.replaying ? S.replaying.registers
      : (S.state && S.state.frames.length ? S.state.frames[0].registers : null);
  const pc = currentPcInThisMethod();
  const insn = S.view && pc != null ? S.view.instructions.find(i => i.dexPc === pc) : null;

  if (!regs || !regs.length) {
    box.innerHTML = '';
    box.appendChild(el('div', 'empty',
      '命中断点后，这里会显示每个寄存器的类型和值，并把这一步中发生变化的寄存器高亮出来。'));
    $('#dataflow').classList.add('hidden');
    return;
  }

  renderDataflow(insn, regs);
  const reads = new Set(insn ? insn.reads : []);
  const writes = new Set(insn ? insn.writes : []);

  box.innerHTML = '';
  for (const r of regs) {
    const row = el('div', 'reg');
    row.dataset.reg = r.reg;
    if (r.changed) row.classList.add('changed');
    if (!r.readable) row.classList.add('unreadable');
    if (writes.has(r.reg)) row.classList.add('is-write');
    else if (reads.has(r.reg)) row.classList.add('is-read');

    const top = el('div', 'top');
    top.appendChild(el('span', 'name', r.name));
    top.appendChild(el('span', 'type', r.type));
    if (r.hint) top.appendChild(el('span', 'hint', r.hint));
    // 二期：可读的原始类型寄存器可以就地改值（挂起、非回放时才显示）。
    // 对象寄存器不给编辑入口——写任意对象需要有效 objectId，本期不做。
    if (!S.replaying && r.readable && !r.expandable) {
      const edit = el('span', 'reg-edit', '改');
      edit.title = `把 ${r.name} 改成别的值，改完单步就能看到影响`;
      clickable(edit, `修改寄存器 ${r.name}`, () => editRegister(r));
      top.appendChild(edit);
    }
    row.appendChild(top);

    const val = el('div', 'val' + (r.expandable ? ' clickable' : ''), r.value);
    if (r.expandable) {
      val.title = '点击展开这个对象的字段';
      clickable(val, `展开对象 ${r.value}`, () => expandObject(r.objectId));
    }
    row.appendChild(val);

    const old = S.prevValues[r.reg];
    if (r.changed && old !== undefined && old !== r.value) {
      row.appendChild(el('div', 'old', `旧值 ${old}`));
    }
    box.appendChild(row);
  }
}

// ── 控制流图 ─────────────────────────────────────────────────────────────
function renderCfg() {
  const panel = $('#panel-cfg');
  panel.innerHTML = '';
  const v = S.view;
  if (!v || !v.blocks.length) { panel.appendChild(el('div', 'empty', '该方法没有可显示的控制流。')); return; }

  const NS = 'http://www.w3.org/2000/svg';
  const W = 190, H = 34, GAP = 16, X = 30;
  const byId = new Map(v.blocks.map(b => [b.startPc, b]));
  const pos = new Map();
  v.blocks.forEach((b, i) => pos.set(b.startPc, { x: X, y: 14 + i * (H + GAP) }));

  const height = 28 + v.blocks.length * (H + GAP);
  const svg = document.createElementNS(NS, 'svg');
  svg.id = 'cfgSvg';
  svg.setAttribute('width', String(X + W + 160));
  svg.setAttribute('height', String(height));

  const defs = document.createElementNS(NS, 'defs');
  defs.innerHTML =
    '<marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">' +
    '<path d="M 0 0 L 10 5 L 0 10 z" fill="#4b5666"/></marker>';
  svg.appendChild(defs);

  // 先画边，避免压住节点
  for (const b of v.blocks) {
    const from = pos.get(b.startPc);
    for (const s of b.successors) {
      const target = byId.get(s) || v.blocks.find(x => s >= x.startPc && s <= x.endPc);
      if (!target) continue;
      const to = pos.get(target.startPc);
      if (!to) continue;
      const y1 = from.y + H / 2, y2 = to.y + H / 2;
      const back = to.y < from.y;
      const bow = back ? W + 90 : W + 40;
      const p = document.createElementNS(NS, 'path');
      p.setAttribute('d', `M${X + W},${y1} C${X + bow},${y1} ${X + bow},${y2} ${X + W},${y2}`);
      p.setAttribute('class', 'cfg-edge' + (b.visited && target.visited ? ' visited' : ''));
      svg.appendChild(p);
    }
  }

  for (const b of v.blocks) {
    const p = pos.get(b.startPc);
    const g = document.createElementNS(NS, 'g');
    g.setAttribute('class', 'cfg-node' + (b.visited ? ' visited' : '') + (b.current ? ' current' : ''));
    const rect = document.createElementNS(NS, 'rect');
    rect.setAttribute('x', String(p.x)); rect.setAttribute('y', String(p.y));
    rect.setAttribute('width', String(W)); rect.setAttribute('height', String(H));
    g.appendChild(rect);
    const t = document.createElementNS(NS, 'text');
    t.setAttribute('x', String(p.x + 10)); t.setAttribute('y', String(p.y + 21));
    t.textContent = `块 ${b.id}   pc ${b.startPc}–${b.endPc}`;
    g.appendChild(t);
    g.style.cursor = 'pointer';
    g.onclick = () => {
      const row = document.querySelector(`#smali .row[data-pc="${b.startPc}"]`);
      if (row) row.scrollIntoView({ block: 'center', behavior: 'smooth' });
    };
    svg.appendChild(g);
  }
  panel.appendChild(svg);

  const legend = el('div', 'tl-info',
    '蓝色 = 执行过的块，黄色 = 当前所在块，灰色 = 尚未走到。点击块可跳到对应指令。');
  panel.appendChild(legend);
}

// ── 调用栈 / 对象图 / 时间线 ──────────────────────────────────────────────
function renderStack() {
  const panel = $('#panel-stack');
  panel.innerHTML = '';
  const st = S.state;
  if (!st || st.status !== 'suspended' || !st.frames.length) {
    panel.appendChild(el('div', 'empty', '命中断点后显示调用栈。'));
    return;
  }
  st.frames.forEach((f, i) => {
    const card = el('div', 'frame' + (i === 0 ? ' top' : '') + (f.hasModel ? '' : ' nomodel'));
    card.appendChild(el('div', null, `${f.fqcn.split('.').pop()}.${f.method}`));
    card.appendChild(el('div', 'loc', `${f.fqcn} · dex_pc ${f.dexPc}${f.hasModel ? '' : ' · 系统方法'}`));
    if (f.hasModel) {
      card.onclick = async () => {
        await openMethodFromFrame(f);
        const fr = await get('/api/frame', { depth: i });
        if (fr) { S.replaying = null; S.state.frames[0] = fr; renderRegisters(); }
      };
    }
    panel.appendChild(card);
  });
}

async function openMethodFromFrame(f) {
  await navigateTo(f.fqcn, f.method, f.signature);
}

async function expandObject(id) {
  if (!id) return;
  $$('#tabs .tab').forEach(t => t.classList.toggle('active', t.dataset.panel === 'object'));
  $$('.panel').forEach(p => p.classList.toggle('active', p.id === 'panel-object'));
  const panel = $('#panel-object');
  panel.innerHTML = '';
  try {
    const n = await get('/api/object', { id });
    if (!n) { panel.appendChild(el('div', 'empty', '该对象已不可读（线程可能已继续运行）。')); return; }
    renderObjectNode(panel, n);
  } catch (e) {
    panel.appendChild(el('div', 'empty', '展开失败：' + e.message));
  }
}

function renderObjectNode(container, n) {
  const box = el('div', 'objnode');
  box.appendChild(el('div', 'head', n.label));
  if (!n.fields.length) box.appendChild(el('div', 'empty', '该对象没有可显示的字段。'));
  for (const f of n.fields) {
    const row = el('div', 'field');
    row.appendChild(el('div', 'fname', f.name));
    row.appendChild(el('div', 'ftype', f.type));
    const v = el('div', 'fval' + (f.expandable ? ' clickable' : ''), f.value);
    if (f.expandable) v.onclick = () => expandObject(f.objectId);
    row.appendChild(v);
    box.appendChild(row);
  }
  if (n.truncated) box.appendChild(el('div', 'empty', `元素较多，仅显示前 ${n.fields.length} 个。`));
  container.appendChild(box);
}

async function refreshTimeline() {
  S.timeline = await get('/api/timeline') || [];
  const panel = $('#panel-timeline');
  panel.innerHTML = '';
  if (!S.timeline.length) {
    panel.appendChild(el('div', 'empty', '单步过程会被逐步记录，可拖动回看任意一步。'));
    return;
  }
  const info = el('div', 'tl-info');
  const range = el('input');
  range.type = 'range'; range.id = 'tlRange';
  range.min = '1'; range.max = String(S.timeline.length); range.value = String(S.timeline.length);
  const regs = el('div', 'tl-regs');

  const show = (i) => {
    const snap = S.timeline[i - 1];
    info.textContent =
      `第 ${snap.seq} 步 · ${snap.fqcn.split('.').pop()}.${snap.method} · dex_pc ${snap.dexPc} · 栈深 ${snap.stackDepth}`;
    regs.innerHTML = '';
    for (const r of snap.registers) {
      if (!r.readable) continue;
      regs.appendChild(el('div', 'tl-reg' + (r.changed ? ' changed' : ''), `${r.name}=${r.value}`));
    }
    S.replaying = (i === S.timeline.length) ? null : snap;
    renderRegisters();
    renderCode();
  };
  range.oninput = () => show(Number(range.value));

  panel.appendChild(info);
  panel.appendChild(range);
  panel.appendChild(regs);
  panel.appendChild(el('div', 'tl-info',
    '这是对已记录快照的回放，不是让程序倒着执行——JDWP 不支持逆执行。'));
  show(S.timeline.length);
}

function renderBreakpoints() {
  const panel = $('#panel-bp');
  panel.innerHTML = '';

  // 预设模板：新手不必自己翻类名找入口，一键把断点下到常见位置。
  if (S.templates && S.templates.length) {
    const bar = el('div', 'tpl-bar');
    bar.appendChild(el('span', 'tpl-label', '一键断在：'));
    for (const t of S.templates) {
      const btn = el('button', 'tpl-btn', `${t.label}（${t.count}）`);
      if (t.hint) btn.title = t.hint;
      btn.onclick = async () => {
        btn.disabled = true;
        try { await api('/api/template', { id: t.id }); await refreshBps(); }
        catch (e) { hint('套用模板失败：' + e.message, 'error'); }
        finally { btn.disabled = false; }
      };
      bar.appendChild(btn);
    }
    panel.appendChild(bar);
  }

  if (!S.bps.length) { panel.appendChild(el('div', 'empty', '尚未设置断点。可以用上面的模板一键下断点。')); return; }
  const head = el('div', 'bprow');
  ['位置', '状态', '命中次数', ''].forEach(h => head.appendChild(el('div', null, h)));
  panel.appendChild(head);
  for (const b of S.bps) {
    const row = el('div', 'bprow');
    const loc = el('div', null, `${b.fqcn.split('.').pop()}.${b.method} @ ${b.dexPc}`);
    if (b.condition) loc.appendChild(el('span', 'bp-cond', ' 条件：' + b.condition));
    row.appendChild(loc);
    const st = el('div', 'st-' + b.state,
      b.state === 'pending' ? '等待类加载' : b.state === 'active' ? '已生效' : '错误');
    if (b.note) st.title = b.note;
    row.appendChild(st);
    row.appendChild(el('div', null, String(b.hitCount)));
    const acts = el('div', 'bp-acts');
    const cond = el('button', null, '条件');
    cond.title = '让它只在特定情况下停：跳过前 N 次命中，或某寄存器等于某值';
    cond.onclick = () => editCondition(b);
    acts.appendChild(cond);
    const del = el('button', null, '删除');
    del.onclick = async () => { await api('/api/bp/remove', { id: b.id }); };
    acts.appendChild(del);
    row.appendChild(acts);
    panel.appendChild(row);
  }
}

async function editCondition(b) {
  const skip = prompt(`断点 #${b.id}：跳过前几次命中？（0 = 不跳过，循环里定位第 N 圈很有用）`,
    '0');
  if (skip === null) return;
  const rspec = prompt('再加「某寄存器等于某值才停」？填 寄存器号=值（如 1=5，即 v1 等于 5）；留空表示不加。', '');
  let reg = '', eq = '';
  if (rspec && rspec.includes('=')) { const [a, v] = rspec.split('='); reg = a.trim(); eq = v.trim(); }
  try {
    await api('/api/bp/cond', { id: b.id, skip: skip.trim() || '0', reg, eq });
    await refreshBps();
  } catch (e) { hint('设置条件失败：' + e.message, 'error'); }
}

// ── 断点与控制 ────────────────────────────────────────────────────────────
async function toggleBp(pc) {
  if (!S.pkg) { hint('请先载入一个应用', 'error'); return; }
  const exist = S.bps.find(b =>
    b.fqcn === S.view.fqcn && b.method === S.view.method && b.signature === S.view.signature && b.dexPc === pc);
  try {
    if (exist) await api('/api/bp/remove', { id: exist.id });
    else await api('/api/bp', { class: S.view.fqcn, method: S.view.method, sig: S.view.signature, pc });
    await refreshBps();
  } catch (e) {
    hint('设置断点失败：' + e.message, 'error');
  }
}

async function refreshBps() {
  S.bps = await get('/api/breakpoints') || [];
  renderBreakpoints();
  if (S.view) renderCode();
}

async function control(action) {
  try {
    S.replaying = null;
    await api('/api/control', { action });
  } catch (e) {
    hint('操作失败：' + e.message, 'error');
  }
}

// ── 状态更新 ─────────────────────────────────────────────────────────────
function applyState(st) {
  const prev = S.state;
  if (prev && prev.frames.length) {
    S.prevValues = {};
    for (const r of prev.frames[0].registers) S.prevValues[r.reg] = r.value;
  }
  S.state = st;
  S.replaying = null;

  const box = $('#status');
  box.className = 'status ' + st.status;
  $('#statusText').textContent = st.message + (st.reason ? `（${st.reason}）` : '');
  $('#deopt').classList.toggle('hidden', !st.deoptWarning);

  const suspended = st.status === 'suspended';
  $$('#controls button[data-action]').forEach(b => {
    b.disabled = b.dataset.action === 'stop' ? st.status === 'idle' : !suspended;
  });

  renderStack();
  renderRegisters();

  // 停在别的方法里时，自动把代码视图切过去——新手不该自己去找类。
  if (suspended && st.frames.length) {
    const f = st.frames[0];
    if (f.hasModel && (f.fqcn !== S.cls || f.method !== S.method || f.signature !== S.sig)) {
      openMethodFromFrame(f).then(() => { if (timelineVisible()) refreshTimeline(); });
      return;
    }
  }
  // 时间线在单步时是实时增长的，但只有它当前可见才重建 DOM——
  // 隐藏时白建看不到，切回该标签时会重新拉（见 tab 点击处）。
  if (S.view) refreshMethodView().then(() => { if (suspended && timelineVisible()) refreshTimeline(); });
  else if (suspended && timelineVisible()) refreshTimeline();
}

const timelineVisible = () => $('#panel-timeline').classList.contains('active');

// ── 改寄存器（二期）──────────────────────────────────────────────────────
async function editRegister(r) {
  const nv = prompt(`把 ${r.name}（${r.type}）改成：`, r.value);
  if (nv === null || nv.trim() === '') return;
  try {
    const res = await api('/api/setreg', { reg: r.reg, value: nv.trim() });
    // 用返回的最新帧更新面板，并让改动的寄存器高亮。
    if (res && res.frame && S.state && S.state.frames.length) {
      S.prevValues = {};
      for (const x of S.state.frames[0].registers) S.prevValues[x.reg] = x.value;
      const f = res.frame;
      f.registers.forEach(x => { x.changed = S.prevValues[x.reg] !== undefined && S.prevValues[x.reg] !== x.value; });
      S.state.frames[0] = f;
      renderRegisters();
      renderCode();
    }
  } catch (e) {
    hint('改寄存器失败：' + e.message, 'error');
  }
}

// ── 悬浮解释 ─────────────────────────────────────────────────────────────
function showTip(e, text) {
  const t = $('#tip');
  t.textContent = text;
  t.classList.remove('hidden');
  moveTip(e);
}
function moveTip(e) {
  const t = $('#tip');
  const x = Math.min(e.clientX + 16, window.innerWidth - t.offsetWidth - 12);
  const y = Math.min(e.clientY + 16, window.innerHeight - t.offsetHeight - 12);
  t.style.left = x + 'px';
  t.style.top = y + 'px';
}
function hideTip() { $('#tip').classList.add('hidden'); }

// ── AI 解释（可选功能）───────────────────────────────────────────────────
async function explain(mode) {
  if (!S.cls || !S.method) { hint('请先选一个方法', 'error'); return; }
  const out = $('#explainOut');
  out.className = 'empty';
  out.textContent = mode === 'registers' ? '正在推测寄存器语义…' : '正在生成讲解…';
  $$('#panel-explain button').forEach(b => { b.disabled = true; });
  try {
    const r = await get('/api/explain', {
      class: S.cls, method: S.method, sig: S.sig,
      pc: currentPcInThisMethod(), mode,
    });
    out.className = '';
    out.textContent = r && r.ok ? r.text : ((r && r.message) || '没有返回内容');
  } catch (e) {
    out.className = 'empty';
    out.textContent = '调用失败：' + e.message;
  } finally {
    $$('#panel-explain button').forEach(b => { b.disabled = false; });
  }
}

$('#btnExplainCode').onclick = () => explain('code');
$('#btnExplainRegs').onclick = () => explain('registers');

// ── AI 设置弹层 ──────────────────────────────────────────────────────────
async function openSettings() {
  const c = await get('/api/config').catch(() => null);
  if (c) {
    $('#cfgBaseUrl').value = c.baseUrl || '';
    $('#cfgModel').value = c.model || '';
    $('#cfgKey').value = '';
    $('#cfgKey').placeholder = c.hasKey ? `已配置（${c.maskedKey}），留空则不改动` : '留空则不启用；填入即启用';
    $('#cfgState').textContent = c.enabled ? `已启用 · 请求地址 ${c.endpoint}` : '未启用（没有 Key 时 AI 功能整体隐藏）';
  }
  $('#settingsMask').classList.remove('hidden');
}
function closeSettings() { $('#settingsMask').classList.add('hidden'); }

async function saveSettings(extra) {
  const params = {
    save: '1',
    baseUrl: $('#cfgBaseUrl').value.trim(),
    model: $('#cfgModel').value.trim(),
    ...extra,
  };
  // 只有用户填了新 key 才发送 apiKey（留空 = 不改动）。
  const k = $('#cfgKey').value.trim();
  if (k) params.apiKey = k;
  const c = await api('/api/config', params);
  $('#cfgKey').value = '';
  $('#cfgKey').placeholder = c.hasKey ? `已配置（${c.maskedKey}），留空则不改动` : '留空则不启用；填入即启用';
  $('#cfgState').textContent = c.enabled ? `已启用 · 请求地址 ${c.endpoint}` : '未启用';
  // 启用状态可能变了：让「AI 解释」标签页出现/消失。
  $('#tabExplain').classList.toggle('hidden', !c.enabled);
  return c;
}

$('#btnSettings').onclick = openSettings;
$('#settingsClose').onclick = closeSettings;
$('#settingsMask').onclick = (e) => { if (e.target.id === 'settingsMask') closeSettings(); };
$('#cfgSave').onclick = async () => {
  try { await saveSettings(); $('#cfgState').textContent = '已保存。' + $('#cfgState').textContent; }
  catch (e) { $('#cfgState').textContent = '保存失败：' + e.message; }
};
$('#cfgClear').onclick = async () => {
  try { await saveSettings({ apiKey: '' }); $('#cfgState').textContent = '已清除 Key，AI 功能已关闭。'; }
  catch (e) { $('#cfgState').textContent = '操作失败：' + e.message; }
};
$('#cfgTest').onclick = async () => {
  $('#cfgState').textContent = '正在测试…';
  try {
    await saveSettings();   // 先存，再用当前配置测
    const r = await api('/api/config/test');
    $('#cfgState').textContent = r.ok ? `✅ 连通正常，返回：${r.reply}` : `❌ ${r.message}`;
  } catch (e) { $('#cfgState').textContent = '❌ ' + e.message; }
};

// ── 事件流 ───────────────────────────────────────────────────────────────
function connectEvents() {
  const es = new EventSource('/api/events');
  es.addEventListener('state', (e) => applyState(JSON.parse(e.data)));
  es.addEventListener('breakpoints', (e) => {
    S.bps = JSON.parse(e.data);
    renderBreakpoints();
    if (S.view) renderCode();
  });
  es.addEventListener('log', (e) => {
    const list = $('#logList');
    list.appendChild(el('div', null, JSON.parse(e.data)));
    list.scrollTop = list.scrollHeight;
    if (list.childElementCount > 400) list.removeChild(list.firstChild);
  });
  es.onerror = () => { /* EventSource 会自动重连 */ };
}

// ── 内嵌终端（xterm.js + 后端 PTY）────────────────────────────────────────
const TERM = { term: null, fit: null, es: null };

function openTerminal() {
  const drawer = $('#termDrawer');
  drawer.classList.remove('hidden');
  if (TERM.term) { TERM.fit && TERM.fit.fit(); TERM.term.focus(); return; }

  const term = new Terminal({
    fontFamily: 'ui-monospace, Menlo, Consolas, monospace',
    fontSize: 13, cursorBlink: true,
    theme: { background: '#14171c', foreground: '#d8dee9', cursor: '#4aa3ff' },
  });
  const fit = new FitAddon.FitAddon();
  term.loadAddon(fit);
  term.open($('#termHost'));
  fit.fit();
  TERM.term = term; TERM.fit = fit;

  const dec = new TextDecoder();
  connectTerm(term.cols, term.rows);

  // 按键 → POST 给 PTY。必须串行：HTTP 请求不保序，并发会让「ls」变「sl」。
  let inQ = Promise.resolve();
  term.onData((d) => {
    inQ = inQ.then(() =>
      fetch('/api/term/input', { method: 'POST', body: new Blob([d]) }).catch(() => {}));
  });
  // 尺寸变化 → 通知后端 ioctl
  term.onResize(({ cols, rows }) => {
    get('/api/term/resize', { cols, rows }).catch(() => {});
  });
  window.addEventListener('resize', () => TERM.fit && TERM.fit.fit());
  setTimeout(() => { fit.fit(); term.focus(); }, 50);

  function connectTerm(cols, rows) {
    const es = new EventSource(`/api/term/open?cols=${cols}&rows=${rows}`);
    TERM.es = es;
    es.addEventListener('out', (e) => {
      // 后端把 PTY 原始字节 base64 过来（裸 base64，不是 JSON），解码后写进 xterm
      const bin = atob(e.data);
      const arr = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
      term.write(dec.decode(arr, { stream: true }));
    });
    es.addEventListener('exit', () => { term.write('\r\n\x1b[90m[终端已退出，点「重开」]\x1b[0m\r\n'); es.close(); });
  }
}

function closeTerminal() { $('#termDrawer').classList.add('hidden'); }

async function restartTerminal() {
  if (TERM.es) TERM.es.close();
  await api('/api/term/close').catch(() => {});
  if (TERM.term) { TERM.term.dispose(); TERM.term = null; }
  openTerminal();
}

$('#btnTerm').onclick = () => {
  const d = $('#termDrawer');
  if (d.classList.contains('hidden')) openTerminal(); else closeTerminal();
};
$('#termCloseBtn').onclick = closeTerminal;
$('#termRestart').onclick = restartTerminal;

// ── 绑定 ─────────────────────────────────────────────────────────────────
$('#loadApp').onclick = loadApp;
$('#classFilter').oninput = () => { if (S.pkg) loadClasses(); };
$('#btnStart').onclick = async () => {
  if (!S.pkg) { hint('请先载入一个应用', 'error'); return; }
  if (!S.bps.length) hint('还没有设置断点：应用会正常启动，但不会停下来。可以先在指令左侧点圆点下个断点。');
  await api('/api/start');
};
$$('#controls button[data-action]').forEach(b => { b.onclick = () => control(b.dataset.action); });
$$('#tabs .tab').forEach(t => {
  t.onclick = () => {
    $$('#tabs .tab').forEach(x => x.classList.toggle('active', x === t));
    $$('.panel').forEach(p => p.classList.toggle('active', p.id === 'panel-' + t.dataset.panel));
    if (t.dataset.panel === 'timeline') refreshTimeline();
  };
});

document.addEventListener('keydown', (e) => {
  if (e.target.tagName === 'INPUT' || e.target.tagName === 'SELECT') return;
  const map = { F8: 'resume', F7: 'into', F6: 'over', F9: 'out' };
  if (map[e.key]) { e.preventDefault(); control(map[e.key]); }
});

connectEvents();
boot();
