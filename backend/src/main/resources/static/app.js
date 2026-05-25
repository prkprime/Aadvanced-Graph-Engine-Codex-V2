'use strict';

const API_BASE = '/api/v1/graphs/default';

// Diagram colors
const NODE_COLOR  = '#4a90d9'; // default blue
const NODE_HL     = '#f5a623'; // k-hop orange
const NODE_IMPACT = '#e94560'; // delete-preview red
const NODE_DIM    = '#1e2a40'; // dimmed (background)
const LINK_COLOR  = '#5577aa';
const LINK_HL     = '#f5a623';
const LINK_IMPACT = '#e94560';
const LINK_DIM    = '#1a2235';

let diagram          = null;
let schemaData       = null;
let impactPreview    = null;
let impactedNodeKeys = new Set(); // nodes currently highlighted red

// Raw-data cache used to build the rows panel
let lastVertices = {}; // { numericId → label }
let lastEdges    = []; // [{ fromVertexId, toVertexId, relations, rowId }]

// ─────────────────────────────────────────────────────────────
// Bootstrap
// ─────────────────────────────────────────────────────────────
window.addEventListener('DOMContentLoaded', async () => {
  initDiagram();
  schemaData = await safeFetch(`${API_BASE}/schema`);
  await loadFullGraph();
});

// ─────────────────────────────────────────────────────────────
// GoJS
// ─────────────────────────────────────────────────────────────
function initDiagram() {
  const $ = go.GraphObject.make;

  diagram = $(go.Diagram, 'myDiagramDiv', {
    layout: $(go.LayeredDigraphLayout, {
      direction: 0,       // left → right; disconnected components stack top → bottom
      layerSpacing: 70,
      columnSpacing: 50,
      setsPortSpots: false,
    }),
    'animationManager.isEnabled': true,
    'undoManager.isEnabled': false,
    'toolManager.mouseWheelBehavior': go.ToolManager.WheelZoom,
    allowDelete: false,
    allowInsert: false,
    padding: new go.Margin(30),
  });

  // ── Node template ──────────────────────────────────────────
  diagram.nodeTemplate = $(go.Node, 'Auto',
    {
      cursor: 'pointer',
      selectionAdornmentTemplate: $(go.Adornment, 'Auto',
        $(go.Shape, 'RoundedRectangle', { fill: null, stroke: NODE_HL, strokeWidth: 3 }),
        $(go.Placeholder, { margin: 0 })
      ),
    },
    new go.Binding('opacity', 'dimmed', d => d ? 0.18 : 1),
    $(go.Shape, 'RoundedRectangle', {
      fill: NODE_COLOR, stroke: null, minSize: new go.Size(120, 36),
    }, new go.Binding('fill', 'color')),
    $(go.TextBlock, {
      margin: new go.Margin(10, 16),
      stroke: 'white',
      font: 'bold 12px "Segoe UI", sans-serif',
      wrap: go.TextBlock.WrapFit,
      maxSize: new go.Size(160, NaN),
      textAlign: 'center',
    }, new go.Binding('text', 'label'))
  );

  // ── Link template ──────────────────────────────────────────
  diagram.linkTemplate = $(go.Link,
    {
      routing: go.Link.AvoidsNodes,
      corner: 8,
      toShortLength: 6,
      fromShortLength: 2,
      cursor: 'pointer',
      selectionAdornmentTemplate: $(go.Adornment,
        $(go.Shape, { isPanelMain: true, stroke: NODE_HL, strokeWidth: 3 }),
        $(go.Shape, { toArrow: 'Standard', fill: NODE_HL, stroke: null, scale: 1.4 })
      ),
    },
    new go.Binding('opacity', 'dimmed', d => d ? 0.1 : 1),
    $(go.Shape, { strokeWidth: 2 }, new go.Binding('stroke', 'color')),
    $(go.Shape, { toArrow: 'Standard', scale: 1.3 },
      new go.Binding('fill', 'color'), new go.Binding('stroke', 'color')),
    $(go.TextBlock, {
      segmentFraction: 0.5,
      segmentOffset: new go.Point(0, -12),
      font: '10px "Segoe UI", sans-serif',
      stroke: '#aabbd0',
      background: 'rgba(13,17,23,0.85)',
      margin: new go.Margin(1, 5),
    }, new go.Binding('text', 'relationLabel'))
  );

  // ── Selection → side panel ────────────────────────────────
  diagram.addDiagramListener('ChangedSelection', () => {
    const sel = diagram.selection.first();
    if (sel instanceof go.Node)      showNodePanel(sel.data.key, sel.data.label);
    else if (sel instanceof go.Link) showEdgePanel(sel.data);
    else                             showDefaultPanel();
  });
}

// ─────────────────────────────────────────────────────────────
// Data loading
// ─────────────────────────────────────────────────────────────
async function loadFullGraph() {
  showMsg('Loading graph…');
  setClearHighlightVisible(false);
  impactPreview    = null;
  impactedNodeKeys = new Set();

  const [vertices, edges] = await Promise.all([
    safeFetch(`${API_BASE}/vertices`),
    safeFetch(`${API_BASE}/edges`),
  ]);

  lastVertices = vertices ?? {};
  lastEdges    = (edges ?? []).map((e, i) => ({ ...e, rowId: i }));

  const nodeData = Object.entries(lastVertices).map(([k, label]) => ({
    key: Number(k), label, color: NODE_COLOR, dimmed: false,
  }));

  // Only fully-active edges go into GoJS (null sides are tombstoned)
  const linkData = lastEdges
    .filter(e => e.fromVertexId != null && e.toVertexId != null)
    .map(e => ({
      from: e.fromVertexId,
      to: e.toVertexId,
      rowId: e.rowId,
      relations: e.relations ?? [],
      relationLabel: (e.relations ?? []).filter(Boolean).join(' / '),
      color: LINK_COLOR,
      dimmed: false,
    }));

  diagram.model = new go.GraphLinksModel(nodeData, linkData);
  document.getElementById('graph-stats').textContent =
    `${nodeData.length} vertices · ${linkData.length} edges`;

  buildRowsPanel();
  showDefaultPanel();
}

// ─────────────────────────────────────────────────────────────
// Rows panel
// ─────────────────────────────────────────────────────────────
function buildRowsPanel() {
  const relNames = schemaData?.relations?.map(r => r.name) ?? [];
  const total    = lastEdges.length;
  const deleted  = lastEdges.filter(e => e.fromVertexId == null && e.toVertexId == null).length;

  document.getElementById('rows-title').textContent =
    `Rows  (${total} total${deleted ? '  ·  ' + deleted + ' deleted' : ''})`;

  // Derive relation column count from first row if schema unavailable
  const relCount = relNames.length || (lastEdges[0]?.relations?.length ?? 0);
  const relHeaders = Array.from({ length: relCount }, (_, i) =>
    `<th>${esc(relNames[i] ?? `Rel ${i + 1}`)}</th>`
  ).join('');

  document.getElementById('rows-thead').innerHTML = `
    <tr>
      <th>#</th><th>From</th><th>To</th>${relHeaders}<th>Status</th>
    </tr>`;

  document.getElementById('rows-tbody').innerHTML = lastEdges.map(e => {
    const fromNull = e.fromVertexId == null;
    const toNull   = e.toVertexId   == null;

    let cls, lbl;
    if      (!fromNull && !toNull) { cls = 's-active';  lbl = 'active'; }
    else if ( fromNull && !toNull) { cls = 's-partial'; lbl = 'from-del'; }
    else if (!fromNull &&  toNull) { cls = 's-partial'; lbl = 'to-del'; }
    else                           { cls = 's-deleted'; lbl = 'deleted'; }

    const fromCell = fromNull ? '—'
      : `${esc(lastVertices[e.fromVertexId] ?? '?')} <span class="badge">#${e.fromVertexId}</span>`;
    const toCell = toNull ? '—'
      : `${esc(lastVertices[e.toVertexId] ?? '?')} <span class="badge">#${e.toVertexId}</span>`;
    const relCells = (e.relations ?? []).map(v => `<td>${esc(v ?? '—')}</td>`).join('');
    const rowCls   = (fromNull && toNull) ? ' class="row-deleted"' : '';

    return `<tr${rowCls}
        data-row-id="${e.rowId}"
        data-from-id="${e.fromVertexId ?? -1}"
        data-to-id="${e.toVertexId ?? -1}">
      <td>${e.rowId}</td>
      <td>${fromCell}</td>
      <td>${toCell}</td>
      ${relCells}
      <td><span class="status-badge ${cls}">${lbl}</span></td>
    </tr>`;
  }).join('');
}

function toggleRowsPanel() {
  const panel   = document.getElementById('rows-panel');
  const chevron = document.getElementById('rows-chevron');
  const closing = !panel.classList.contains('collapsed');
  panel.classList.toggle('collapsed', closing);
  chevron.textContent = closing ? '▶' : '▼';
}

function openRowsPanel() {
  const panel   = document.getElementById('rows-panel');
  const chevron = document.getElementById('rows-chevron');
  panel.classList.remove('collapsed');
  chevron.textContent = '▼';
}

function refreshRowsImpactHighlight() {
  document.querySelectorAll('#rows-tbody tr').forEach(row => {
    const from = Number(row.dataset.fromId);
    const to   = Number(row.dataset.toId);
    const hit  = impactedNodeKeys.has(from) || impactedNodeKeys.has(to);
    row.classList.toggle('row-impacted', hit);
  });
}

// ─────────────────────────────────────────────────────────────
// Panel rendering
// ─────────────────────────────────────────────────────────────
function setPanelHTML(html) {
  document.getElementById('panel-content').innerHTML = html;
}
function showMsg(msg) {
  setPanelHTML(`<div class="panel-empty">${msg}</div>`);
}

function showDefaultPanel() {
  setPanelHTML(`
    <div class="panel-section">
      <div class="panel-title">Graph Explorer</div>
      <p class="hint">Click a <strong>node</strong> to inspect it and run operations.<br>
      Click an <strong>edge</strong> to view its relations or delete it.</p>
    </div>
    <div class="panel-section">
      <div class="panel-subtitle">Supported operations</div>
      <ul class="op-list">
        <li>🔍 K-hop neighborhood exploration</li>
        <li>🗑 Vertex soft-delete with cascade options</li>
        <li>👁 Impact preview — highlights in red on graph + rows</li>
        <li>✂️ Edge deletion by row</li>
      </ul>
    </div>
  `);
}

async function showNodePanel(nodeKey, labelHint) {
  showMsg('Loading vertex…');

  const [details, stats, attrs] = await Promise.all([
    safeFetch(`${API_BASE}/vertices/${nodeKey}`),
    safeFetch(`${API_BASE}/vertices/${nodeKey}/stats`),
    safeFetch(`${API_BASE}/vertices/${nodeKey}/attributes`),
  ]);

  const label     = details?.sourceLabel ?? labelHint ?? `#${nodeKey}`;
  const attrNames = schemaData?.attributes?.map(a => a.name) ?? [];
  const attrRows  = attrs?.resolvedAttributes ?? [];

  let attrsHTML = '';
  if (attrRows.length > 0 && (attrRows[0]?.length ?? 0) > 0) {
    const cols = attrNames.length ? attrNames : attrRows[0].map((_, i) => `Attr ${i + 1}`);
    attrsHTML = `
      <div class="panel-section">
        <div class="panel-subtitle">Attributes</div>
        <div class="table-wrap">
          <table class="attr-table">
            <thead><tr>${cols.map(c => `<th>${esc(c)}</th>`).join('')}</tr></thead>
            <tbody>${attrRows.map(r =>
              `<tr>${r.map(v => `<td>${esc(v ?? '—')}</td>`).join('')}</tr>`
            ).join('')}</tbody>
          </table>
        </div>
      </div>`;
  }

  const out = stats?.outgoingEdgeCount ?? '—';
  const inc = stats?.incomingEdgeCount ?? '—';
  const tot = stats != null ? stats.outgoingEdgeCount + stats.incomingEdgeCount : '—';

  setPanelHTML(`
    <div class="panel-section">
      <div class="panel-title">${esc(label)}</div>
      <div class="kv"><span class="kv-k">Numeric ID</span><span class="kv-v">${nodeKey}</span></div>
      <div class="kv"><span class="kv-k">Source ID</span><span class="kv-v">${esc(details?.sourceId ?? '—')}</span></div>
    </div>

    <div class="panel-section">
      <div class="panel-subtitle">Edge Stats</div>
      <div class="stat-row">
        <div class="stat-box"><div class="stat-n">${out}</div><div class="stat-l">Out</div></div>
        <div class="stat-box"><div class="stat-n">${inc}</div><div class="stat-l">In</div></div>
        <div class="stat-box"><div class="stat-n">${tot}</div><div class="stat-l">Total</div></div>
      </div>
    </div>

    ${attrsHTML}

    <div class="panel-section">
      <div class="panel-subtitle">K-Hop Explorer</div>
      <div class="form-row">
        <label>K <input id="khop-k" type="number" value="1" min="1" max="10"></label>
        <label>Direction
          <select id="khop-dir">
            <option value="BOTH">Both</option>
            <option value="OUTGOING">Outgoing</option>
            <option value="INCOMING">Incoming</option>
          </select>
        </label>
      </div>
      <button class="btn btn-primary w100" onclick="runKHop(${nodeKey})">🔍 Explore Neighborhood</button>
    </div>

    <div class="panel-section">
      <div class="panel-subtitle">Delete Vertex</div>
      <div class="checkbox-row"><label><input type="checkbox" id="del-ds"> Cascade downstream</label></div>
      <div class="checkbox-row"><label><input type="checkbox" id="del-us"> Cascade upstream</label></div>
      <button class="btn btn-warn w100" style="margin-top:10px"
              onclick="runPreviewImpact(${nodeKey})">👁 Preview Impact</button>
      <div id="impact-box" class="impact-box" style="display:none"></div>
      <button id="btn-del-vertex" class="btn btn-danger w100"
              style="display:none;margin-top:8px"
              onclick="runDeleteVertex(${nodeKey})">🗑 Confirm Delete</button>
    </div>
  `);
}

function showEdgePanel(link) {
  const fromLabel = labelOf(link.from);
  const toLabel   = labelOf(link.to);
  const relNames  = schemaData?.relations?.map(r => r.name) ?? [];

  const relRows = (link.relations ?? []).length
    ? (link.relations ?? []).map((v, i) => `
        <div class="kv">
          <span class="kv-k">${esc(relNames[i] ?? `Relation ${i + 1}`)}</span>
          <span class="kv-v">${esc(v ?? '—')}</span>
        </div>`).join('')
    : '<div class="hint">No relations mapped</div>';

  setPanelHTML(`
    <div class="panel-section">
      <div class="panel-title">Edge</div>
      <div class="edge-arrow">${esc(fromLabel)} → ${esc(toLabel)}</div>
      <div class="kv"><span class="kv-k">Row ID</span><span class="kv-v">${link.rowId}</span></div>
    </div>
    <div class="panel-section">
      <div class="panel-subtitle">Relations</div>
      ${relRows}
    </div>
    <div class="panel-section">
      <button class="btn btn-danger w100" onclick="runDeleteEdge(${link.rowId})">🗑 Delete Edge</button>
    </div>
  `);
}

// ─────────────────────────────────────────────────────────────
// Operations
// ─────────────────────────────────────────────────────────────
async function runKHop(nodeKey) {
  const k   = Math.max(1, Number(document.getElementById('khop-k').value) || 1);
  const dir = document.getElementById('khop-dir').value;

  const data = await safeFetch(
    `${API_BASE}/vertices/${nodeKey}/neighbors?k=${k}&direction=${dir}`);
  if (!data) { alert('K-hop request failed'); return; }

  const subNodes = new Set(Object.keys(data.vertices ?? {}).map(Number));
  const subEdges = new Set((data.edges ?? []).map(e => `${e.fromVertexId}|${e.toVertexId}`));

  impactedNodeKeys = new Set(); // clear any impact highlight
  diagram.startTransaction('khop');
  diagram.nodes.each(n => {
    const hit = subNodes.has(n.data.key);
    diagram.model.setDataProperty(n.data, 'color',  hit ? NODE_HL : NODE_DIM);
    diagram.model.setDataProperty(n.data, 'dimmed', !hit);
  });
  diagram.links.each(l => {
    const hit = subEdges.has(`${l.data.from}|${l.data.to}`);
    diagram.model.setDataProperty(l.data, 'color',  hit ? LINK_HL : LINK_DIM);
    diagram.model.setDataProperty(l.data, 'dimmed', !hit);
  });
  diagram.commitTransaction('khop');
  refreshRowsImpactHighlight();
  setClearHighlightVisible(true);
}

async function runPreviewImpact(nodeKey) {
  const ds = document.getElementById('del-ds').checked;
  const us = document.getElementById('del-us').checked;

  const impacted = await postJSON(`${API_BASE}/vertices/impacted`,
    { nodeId: nodeKey, downStream: ds, upstream: us });

  impactPreview = { nodeId: nodeKey, downStream: ds, upstream: us };
  const entries = Object.entries(impacted ?? {});

  // Highlight impacted nodes red on diagram + open rows panel
  applyImpactHighlight(entries.map(([id]) => Number(id)));

  const box        = document.getElementById('impact-box');
  const confirmBtn = document.getElementById('btn-del-vertex');

  if (!entries.length) {
    box.innerHTML = '<div class="hint">No active vertices would be affected.</div>';
    box.style.display = 'block';
    confirmBtn.style.display = 'none';
    return;
  }

  box.innerHTML = `
    <div class="impact-hdr">⚠ ${entries.length} vertex${entries.length > 1 ? 'es' : ''} will be deleted:</div>
    <ul class="impact-list">
      ${entries.map(([id, lbl]) =>
        `<li>${esc(lbl)} <span class="badge">#${id}</span></li>`
      ).join('')}
    </ul>`;
  box.style.display        = 'block';
  confirmBtn.style.display = 'block';
}

function applyImpactHighlight(nodeIds) {
  impactedNodeKeys = new Set(nodeIds);

  diagram.startTransaction('impact');
  diagram.nodes.each(n => {
    const hit = impactedNodeKeys.has(n.data.key);
    diagram.model.setDataProperty(n.data, 'color',  hit ? NODE_IMPACT : NODE_DIM);
    diagram.model.setDataProperty(n.data, 'dimmed', !hit);
  });
  diagram.links.each(l => {
    // Highlight a link if either endpoint is impacted
    const hit = impactedNodeKeys.has(l.data.from) || impactedNodeKeys.has(l.data.to);
    diagram.model.setDataProperty(l.data, 'color',  hit ? LINK_IMPACT : LINK_DIM);
    diagram.model.setDataProperty(l.data, 'dimmed', !hit);
  });
  diagram.commitTransaction('impact');

  setClearHighlightVisible(true);
  refreshRowsImpactHighlight();
  openRowsPanel(); // auto-open so user sees the affected rows
}

async function runDeleteVertex(nodeKey) {
  if (!impactPreview) return;
  const res = await postJSON(`${API_BASE}/vertices/delete`, impactPreview);
  if (res?.success) {
    impactPreview = null;
    await loadFullGraph();
  } else {
    alert(res?.message ?? 'Delete failed');
  }
}

async function runDeleteEdge(rowId) {
  if (!confirm(`Delete edge (row #${rowId})?`)) return;
  const res  = await fetch(`${API_BASE}/edges/${rowId}`, { method: 'DELETE' });
  const data = await res.json().catch(() => null);
  if (data?.success) {
    await loadFullGraph();
  } else {
    alert(data?.message ?? 'Delete failed');
  }
}

function clearHighlight() {
  impactedNodeKeys = new Set();

  diagram.startTransaction('clear');
  diagram.nodes.each(n => {
    diagram.model.setDataProperty(n.data, 'color',  NODE_COLOR);
    diagram.model.setDataProperty(n.data, 'dimmed', false);
  });
  diagram.links.each(l => {
    diagram.model.setDataProperty(l.data, 'color',  LINK_COLOR);
    diagram.model.setDataProperty(l.data, 'dimmed', false);
  });
  diagram.commitTransaction('clear');

  setClearHighlightVisible(false);
  refreshRowsImpactHighlight();
}

// ─────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────
function setClearHighlightVisible(show) {
  document.getElementById('btn-clear').style.display = show ? 'inline-flex' : 'none';
}

function labelOf(key) {
  return diagram.findNodeForKey(key)?.data?.label ?? `#${key}`;
}

function esc(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

async function safeFetch(url) {
  try {
    const r = await fetch(url);
    return r.ok ? r.json() : null;
  } catch { return null; }
}

async function postJSON(url, body) {
  try {
    const r = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    return r.ok ? r.json() : null;
  } catch { return null; }
}
