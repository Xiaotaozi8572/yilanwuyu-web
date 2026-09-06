import { api, ReauthenticationRequiredError, StaleVersionError } from './api.js';
import { leaveDetail, setConsent, setList, showDetail, snapshot } from './state.js';

const byId = (id) => document.getElementById(id);
const status = (message) => { byId('status').textContent = message; };
const text = (value) => value == null ? 'Unavailable' : typeof value === 'object' ? JSON.stringify(value) : String(value);
const CONSENT_CATEGORIES = ['PREFERENCE', 'MASTERY', 'MISCONCEPTION', 'REFLECTION'];

function syncConsentInputs() {
  const enabled = byId('long-term-enabled').checked;
  document.querySelectorAll('input[name="category"]').forEach((box) => { box.disabled = !enabled; });
  const retention = byId('retention-days');
  retention.disabled = !enabled;
  retention.required = enabled;
}

function renderConsent(consent) {
  setConsent(consent);
  byId('long-term-enabled').checked = Boolean(consent.long_term_enabled);
  byId('retention-days').value = '';
  byId('consent-version').textContent = `Version: ${text(consent.version)}`;
  const allowedCategories = consent.allowed_categories || [];
  document.querySelectorAll('input[name="category"]').forEach((box) => {
    box.checked = CONSENT_CATEGORIES.includes(box.value) && allowedCategories.includes(box.value);
  });
  syncConsentInputs();
}

function populateFilter(id, values, allLabel) {
  const select = byId(id); const selected = select.value;
  select.replaceChildren();
  const all = document.createElement('option'); all.value = ''; all.textContent = allLabel; select.append(all);
  [...new Set(values.filter((value) => typeof value === 'string' && value.length > 0))].sort().forEach((value) => {
    const option = document.createElement('option'); option.value = value; option.textContent = value; select.append(option);
  });
  select.value = [...select.options].some((option) => option.value === selected) ? selected : '';
}

function renderList(page) {
  const items = page.items || []; setList(items); const list = byId('memory-list'); list.replaceChildren();
  populateFilter('type-filter', items.map((memory) => memory.type), 'All types');
  populateFilter('status-filter', items.map((memory) => memory.status), 'All statuses');
  items.forEach((memory) => {
    const button = document.createElement('button'); button.type = 'button';
    button.textContent = `${text(memory.type)} | ${text(memory.status)} | ${text(memory.display_value)}`;
    button.addEventListener('click', () => loadDetail(memory.id));
    const item = document.createElement('li'); item.append(button); list.append(item);
  });
}

function renderDetail(detail) {
  showDetail(detail); byId('detail').hidden = false; const fields = byId('memory-fields'); fields.replaceChildren();
  [['Type', detail.type], ['Status', detail.status], ['Confirmation', detail.confirmation_status], ['Effective time', detail.effective_at], ['Expiry time', detail.expires_at], ['Confidence', detail.confidence_band], ['Minimized provenance', detail.source_summary], ['Usage timeline', detail.usage_history]].forEach(([name, value]) => {
    const term = document.createElement('dt'); term.textContent = name;
    const description = document.createElement('dd'); description.textContent = text(value); fields.append(term, description);
  });
  byId('current-value').textContent = `Current value: ${text(detail.display_value)}`;
}

function filters() { return { type: byId('type-filter').value, status: byId('status-filter').value }; }
async function load() { try { const [consent, memories] = await Promise.all([api.consent(), api.memories(filters())]); renderConsent(consent); renderList(memories); status('Control center loaded.'); } catch (error) { report(error); } }
async function loadDetail(id) { try { renderDetail(await api.memory(id)); status('Memory detail loaded.'); } catch (error) { report(error); } }
function report(error) { if (error instanceof StaleVersionError) { status('This record changed. Reload before trying again.'); } else if (error instanceof ReauthenticationRequiredError) { status('Reauthentication is required before this action can continue.'); } else { status('The control center is currently unavailable.'); } }
async function mutate(action, confirmation) { const detail = snapshot().detail; if (!detail || !window.confirm(confirmation)) return; try { const updated = await action(detail); if (updated) renderDetail(updated); status('Memory updated.'); } catch (error) { report(error); } }

byId('reload').addEventListener('click', load);
byId('back-to-list').addEventListener('click', () => { leaveDetail(); byId('detail').hidden = true; byId('forget-receipt').textContent = ''; });
byId('type-filter').addEventListener('change', load);
byId('status-filter').addEventListener('change', load);
byId('long-term-enabled').addEventListener('change', syncConsentInputs);
byId('consent-form').addEventListener('submit', async (event) => {
  event.preventDefault(); const consent = snapshot().consent; if (!consent) return;
  const enabled = byId('long-term-enabled').checked;
  const allowed = [...document.querySelectorAll('input[name="category"]:checked')].map((box) => box.value);
  let value;
  if (!enabled) {
    value = { long_term_enabled: false, allowed_categories: [], retention_days: null };
  } else {
    const retention = Number(byId('retention-days').value);
    if (allowed.length === 0 || !Number.isInteger(retention) || retention < 1) {
      status('Enabled consent needs at least one category and a positive whole number of retention days.'); return;
    }
    value = { long_term_enabled: true, allowed_categories: allowed, retention_days: retention };
  }
  try { renderConsent(await api.saveConsent(consent.version, value)); status('Consent saved.'); } catch (error) { report(error); }
});
byId('confirm-memory').addEventListener('click', () => mutate((detail) => api.confirm(detail.id, detail.version), 'Confirm this memory?'));
byId('disable-memory').addEventListener('click', () => mutate((detail) => api.disable(detail.id, detail.version), 'Disable this memory? It will no longer be active.'));
byId('correction-form').addEventListener('submit', (event) => { event.preventDefault(); mutate((detail) => api.correct(detail.id, detail.version, { corrected_value: { value: byId('corrected-value').value }, reason: byId('correction-reason').value }), 'Submit this correction? The current and edited values are shown on this page.'); });
byId('forget-memory').addEventListener('click', async () => { const detail = snapshot().detail; if (!detail || !window.confirm('Request forgetting? This creates a blocked deletion receipt; it does not confirm erasure.')) return; try { const receipt = await api.forget(detail.id, detail.version); if (receipt && receipt.state === 'BLOCKED') { byId('forget-receipt').textContent = `Forgetting receipt accepted: BLOCKED (${text(receipt.request_id)}).`; leaveDetail(); byId('detail').hidden = true; await load(); } else { status('Forget request did not produce an accepted BLOCKED receipt.'); } } catch (error) { report(error); } });

load();
