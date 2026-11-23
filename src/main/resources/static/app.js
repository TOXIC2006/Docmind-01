'use strict';

const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => [...document.querySelectorAll(selector)];
const state = { token: sessionStorage.getItem('docmind-token'), documents: [], user: null, mode: 'chat', registering: false, epoch: 0, busy: false, queryController: null };
const requests = new Set();

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}
function icon(name) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('class', 'icon');
  svg.setAttribute('aria-hidden', 'true');
  const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  use.setAttribute('href', `#i-${name}`);
  svg.append(use);
  return svg;
}
function notice(message, error = false) {
  $('#global-message').textContent = message;
  $('#global-message').hidden = !message;
  $('#global-message').classList.toggle('error', error);
}
function authMessage(message, success = false) {
  $('#auth-message').textContent = message;
  $('#auth-message').classList.toggle('success', success);
}
function clearResults() {
  ['answer-section', 'sources-section', 'diagnostics'].forEach(id => { $(`#${id}`).hidden = true; });
  $('#research-empty').hidden = false;
  $('#answer-body').replaceChildren();
  $('#sources-list').replaceChildren();
  $('#asked-query').textContent = '';
  $('#diagnostic-values').replaceChildren();
  $('#rewritten-query').textContent = '';
  $('#trace-warnings').textContent = '';
}
function signOut(message = '') {
  state.epoch += 1;
  requests.forEach(controller => controller.abort());
  requests.clear();
  state.token = null;
  state.user = null;
  state.documents = [];
  state.queryController = null;
  sessionStorage.removeItem('docmind-token');
  $('#app').hidden = true;
  $('#auth-view').hidden = false;
  $('#query').value = '';
  $('#password').value = '';
  $('#library-filter').value = '';
  $('#upload-input').value = '';
  $('#account-name').textContent = 'Your account';
  if ($('#delete-dialog').open) $('#delete-dialog').close('cancel');
  renderDocuments();
  clearResults();
  queryBusy(false);
  notice('');
  authMessage(message);
}
async function api(path, { method = 'GET', body, authenticated = true, controller = new AbortController() } = {}) {
  const epoch = state.epoch;
  requests.add(controller);
  const timeout = setTimeout(() => controller.abort('timeout'), 180000);
  const headers = {};
  if (authenticated && state.token) headers.Authorization = `Bearer ${state.token}`;
  if (body && !(body instanceof FormData)) headers['Content-Type'] = 'application/json';
  try {
    const response = await fetch(path, { method, headers, signal: controller.signal, body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined });
    if (epoch !== state.epoch) throw new DOMException('Session changed', 'AbortError');
    const data = await response.json().catch(() => ({}));
    if (epoch !== state.epoch) throw new DOMException('Session changed', 'AbortError');
    if (response.status === 401 && authenticated) {
      signOut('Your session has expired. Please sign in again.');
      throw new DOMException('Session expired', 'AbortError');
    }
    if (!response.ok) throw new Error(data.message || data.error || `Request failed (${response.status}). Please retry.`);
    return data;
  } catch (error) {
    if (controller.signal.reason === 'timeout') throw new Error('The request timed out. Check the backend services and try again.');
    if (error instanceof TypeError) throw new Error('Cannot reach DocMind. Start the backend and check your connection.');
    throw error;
  } finally {
    clearTimeout(timeout);
    requests.delete(controller);
  }
}
function route() {
  const view = ['workspace', 'library', 'pipeline'].includes(location.hash.slice(1)) ? location.hash.slice(1) : 'workspace';
  ['workspace', 'library', 'pipeline'].forEach(name => { $(`#${name}-view`).hidden = name !== view; });
  $$('[data-view]').forEach(link => {
    if (link.dataset.view === view) link.setAttribute('aria-current', 'page');
    else link.removeAttribute('aria-current');
  });
  $('#page-label').textContent = { workspace: 'Research desk', library: 'Document library', pipeline: 'How it works' }[view];
  if (!$('#app').hidden) $('#main').focus({ preventScroll: true });
}
async function enterWorkspace() {
  const user = await api('/docmind/auth/me');
  state.user = user;
  $('#account-name').textContent = user.username;
  $('#account-initial').textContent = user.username.slice(0, 1).toUpperCase();
  $('#auth-view').hidden = true;
  $('#app').hidden = false;
  $('#password').value = '';
  route();
  await loadDocuments();
}
function fileSize(bytes) {
  return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
}
function renderDocuments() {
  const documents = state.documents;
  $('#document-count').textContent = documents.length;
  $('#nav-doc-count').textContent = documents.length;
  $('#chunk-count').textContent = documents.reduce((total, doc) => total + (doc.chunksCreated || 0), 0).toLocaleString();
  $$('.collection-meter span').forEach((node, i) => node.classList.toggle('filled', i < Math.min(10, documents.length)));
  const selection = $('#document-scope').value;
  $('#document-scope').replaceChildren(new Option('All my documents', ''));
  documents.filter(doc => doc.status === 'indexed').forEach(doc => $('#document-scope').add(new Option(doc.fileName, doc.id)));
  if (documents.some(doc => doc.id === selection && doc.status === 'indexed')) $('#document-scope').value = selection;
  else if (selection) { state.queryController?.abort(); clearResults(); }
  const filtered = documents.filter(doc => doc.fileName.toLowerCase().includes($('#library-filter').value.toLowerCase()));
  $('#library-list').replaceChildren();
  $('#library-empty').hidden = filtered.length > 0;
  $('#library-empty h2').textContent = documents.length ? 'No matching documents.' : 'No documents yet.';
  $('#library-empty p').textContent = documents.length ? 'Try a different filename in the filter.' : 'Upload your first document to start a grounded conversation.';
  filtered.forEach(doc => {
    const card = element('article', 'card document-card');
    const docIcon = element('div', 'doc-icon'); docIcon.append(icon('doc'));
    const status = element('span', `status ${doc.status}`, doc.status === 'indexed' ? 'Ready to explore' : doc.status === 'failed' ? 'Indexing failed' : 'Processing');
    const actions = element('div', 'document-actions');
    const ask = element('button', 'button secondary', 'Ask this document');
    ask.disabled = doc.status !== 'indexed';
    ask.addEventListener('click', () => {
      state.queryController?.abort(); clearResults();
      $('#document-scope').value = doc.id;
      location.hash = 'workspace';
      $('#query').focus();
    });
    const remove = element('button', 'icon-button'); remove.append(icon('trash'));
    remove.setAttribute('aria-label', `Delete ${doc.fileName}`);
    remove.addEventListener('click', () => deleteDocument(doc));
    actions.append(ask, remove);
    card.append(docIcon, element('h2', '', doc.fileName), element('p', '', `${fileSize(doc.fileSize)} · ${doc.chunksCreated || 0} chunks`), status);
    if (doc.status === 'failed' && doc.message) card.append(element('p', 'helper', doc.message));
    card.append(actions);
    $('#library-list').append(card);
  });
}
async function loadDocuments() {
  $('#refresh-documents').disabled = true;
  try {
    const documents = await api('/api/v1/documents');
    state.documents = documents;
    renderDocuments();
  } catch (error) {
    if (error.name !== 'AbortError') notice(error.message, true);
  } finally { $('#refresh-documents').disabled = false; }
}
async function deleteDocument(doc) {
  $('#delete-description').textContent = doc.fileName;
  const dialog = $('#delete-dialog');
  dialog.returnValue = '';
  dialog.showModal();
  const result = await new Promise(resolve => dialog.addEventListener('close', () => resolve(dialog.returnValue), { once: true }));
  if (result !== 'delete') return;
  try {
    await api(`/api/v1/documents/${encodeURIComponent(doc.id)}`, { method: 'DELETE' });
    state.queryController?.abort(); clearResults();
    notice(`${doc.fileName} was deleted.`);
    await loadDocuments();
  } catch (error) { if (error.name !== 'AbortError') notice(error.message, true); }
}
function queryBusy(busy) {
  state.busy = busy;
  $('#ask-button').disabled = busy;
  $('#query-form').setAttribute('aria-busy', String(busy));
  $$('[data-mode]').forEach(button => { button.disabled = busy; });
  $('#ask-button').replaceChildren(busy ? 'Finding evidence…' : state.mode === 'chat' ? 'Find an answer' : 'Search passages', icon('arrow'));
}
function renderSources(sources) {
  $('#sources-count').textContent = sources.length;
  $('#sources-section').hidden = !sources.length;
  $('#sources-list').replaceChildren();
  sources.forEach(source => {
    const card = element('article', 'card source-card');
    card.id = `source-${source.citationId}`;
    card.tabIndex = -1;
    const heading = element('div', 'source-heading');
    heading.append(element('span', '', source.citationId), element('strong', '', source.fileName));
    const meta = element('div', 'source-meta');
    meta.append(element('span', '', source.pageNumber == null ? 'Text passage' : `Page ${source.pageNumber}`), element('span', '', `Chunk ${source.chunkIndex ?? '—'}`));
    const details = element('details');
    details.append(element('summary', '', 'Retrieval scores & chunk ID'));
    const scores = element('div', 'source-meta');
    [['Semantic', source.similarityScore], ['BM25', source.bm25Score], ['RRF', source.fusionScore], ['Rerank', source.rerankScore]].forEach(([label, value]) => {
      scores.append(element('span', '', `${label}: ${value == null ? 'not matched' : Number(value).toFixed(3)}`));
    });
    details.append(scores, element('p', 'helper', `Chunk ID: ${source.chunkId}`));
    card.append(heading, element('p', '', source.snippet), meta, details);
    $('#sources-list').append(card);
  });
}
function renderAnswer(text, sources) {
  const ids = new Set(sources.map(source => source.citationId));
  $('#answer-body').replaceChildren();
  String(text).split(/(\[S\d+\])/g).forEach(part => {
    const id = part.slice(1, -1);
    if (/^\[S\d+\]$/.test(part) && ids.has(id)) {
      const button = element('button', 'cite-button', part);
      button.setAttribute('aria-label', `View source ${id}`);
      button.addEventListener('click', () => {
        const source = document.getElementById(`source-${id}`);
        source.scrollIntoView({ block: 'center', behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' });
        source.focus({ preventScroll: true });
      });
      $('#answer-body').append(button);
    } else $('#answer-body').append(document.createTextNode(part));
  });
}
function renderTrace(trace, cache, usage) {
  $('#diagnostics').hidden = !trace;
  if (!trace) return;
  $('#diagnostic-values').replaceChildren();
  const reduction = trace.contextCharactersBefore ? Math.round(100 * (1 - trace.contextCharactersAfter / trace.contextCharactersBefore)) : 0;
  const rows = [['Strategy', trace.retrievalMode], ['Semantic matches', trace.semanticCandidates], ['BM25 matches', trace.lexicalCandidates], ['Fused candidates', trace.fusedCandidates], ['Context retained', `${trace.contextCharactersAfter.toLocaleString()} chars`], ['Context reduction', `${reduction}%`], ['Retrieval time', `${trace.elapsedMs.toLocaleString()} ms`]];
  if (cache) rows.push(['Answer cache', cache.status], ['Cache scope', 'Selected document + account']);
  if (cache?.similarity != null) rows.push(['Cache similarity', `${(cache.similarity * 100).toFixed(1)}%`]);
  if (cache?.status.endsWith('_HIT')) rows.push(['Cached answer age', `${cache.ageSeconds} s`]);
  if (usage) rows.push(['Estimated answer prompt', `${usage.estimatedPromptTokensBefore} → ${usage.estimatedPromptTokensAfter} tokens`], ['Answer input tokens', usage.promptTokens ?? 'Not reported'], ['Answer output tokens', usage.completionTokens ?? 'Not reported']);
  rows.forEach(([label, value]) => {
    const row = element('div'); row.append(element('dt', '', label), element('dd', '', value)); $('#diagnostic-values').append(row);
  });
  $('#rewritten-query').textContent = `${trace.rewriteApplied ? 'Rewritten query' : 'Original query retained'}: ${trace.rewrittenQuery}`;
  $('#trace-warnings').replaceChildren(...(trace.warnings || []).map(warning => element('p', '', warning)));
}

$('#auth-form').addEventListener('submit', async event => {
  event.preventDefault();
  const registering = state.registering;
  $('#auth-submit').disabled = true;
  $('#auth-switch').disabled = true;
  authMessage(registering ? 'Creating your account…' : 'Signing in…', true);
  try {
    const body = { email: $('#email').value.trim(), password: $('#password').value };
    if (registering) body.username = $('#username').value.trim();
    const data = await api(`/docmind/auth/${registering ? 'register' : 'login'}`, { method: 'POST', authenticated: false, body });
    if (registering) {
      authMessage('Check your inbox to verify your email, then sign in.', true);
      $('#password').value = '';
    } else {
      if (!data.token) throw new Error('No sign-in token was returned. Please retry.');
      state.token = data.token;
      sessionStorage.setItem('docmind-token', data.token);
      await enterWorkspace();
    }
  } catch (error) { if (error.name !== 'AbortError') authMessage(error.message); }
  finally { $('#auth-submit').disabled = false; $('#auth-switch').disabled = false; }
});
$('#auth-switch').addEventListener('click', () => {
  state.registering = !state.registering;
  $('#username-field').hidden = !state.registering;
  $('#username').required = state.registering;
  $('#auth-title').textContent = state.registering ? 'Make room for clarity.' : 'Welcome back.';
  $('#auth-subtitle').textContent = state.registering ? 'Create your private document workspace.' : 'Sign in to your private research workspace.';
  $('#auth-submit').replaceChildren(state.registering ? 'Create account' : 'Enter workspace', icon('arrow'));
  $('#auth-switch').textContent = state.registering ? 'Already have an account? Sign in' : 'New here? Create an account';
  $('#password').autocomplete = state.registering ? 'new-password' : 'current-password';
  authMessage('');
});
$('#resend').addEventListener('click', async () => {
  if (!$('#email').reportValidity()) return;
  $('#resend').disabled = true;
  try {
    const result = await api('/docmind/auth/resend', { method: 'POST', authenticated: false, body: { email: $('#email').value.trim() } });
    authMessage(result.message, true);
  } catch (error) { if (error.name !== 'AbortError') authMessage(error.message); }
  finally { $('#resend').disabled = false; }
});
$('#logout').addEventListener('click', () => signOut());
$('#refresh-documents').addEventListener('click', () => { notice(''); loadDocuments(); });
$('#library-filter').addEventListener('input', renderDocuments);
$$('[data-upload]').forEach(button => button.addEventListener('click', () => $('#upload-input').click()));
$('#upload-input').addEventListener('change', async event => {
  const file = event.target.files[0];
  if (!file) return;
  if (!/\.(pdf|docx|txt|md)$/i.test(file.name) || file.size === 0 || file.size > 25 * 1024 * 1024) {
    notice('Choose a non-empty PDF, DOCX, TXT or Markdown file up to 25 MB.', true);
    event.target.value = '';
    return;
  }
  $$('[data-upload]').forEach(button => { button.disabled = true; });
  notice(`Indexing ${file.name}. This may take a moment…`);
  try {
    const form = new FormData(); form.append('file', file);
    const result = await api('/api/v1/upload_document', { method: 'POST', body: form });
    notice(result.status === 'indexed' ? `${file.name} is ready. ${result.chunksCreated} passages indexed.` : result.message || 'Document processing failed.', result.status !== 'indexed');
    await loadDocuments();
  } catch (error) { if (error.name !== 'AbortError') notice(error.message, true); }
  finally { event.target.value = ''; $$('[data-upload]').forEach(button => { button.disabled = false; }); }
});
$$('[data-mode]').forEach(button => button.addEventListener('click', () => {
  state.mode = button.dataset.mode;
  $$('[data-mode]').forEach(item => item.setAttribute('aria-pressed', String(item === button)));
  queryBusy(false);
}));
$$('[data-query]').forEach(button => button.addEventListener('click', () => { $('#query').value = button.dataset.query; $('#query').focus(); }));
$('#document-scope').addEventListener('change', () => { state.queryController?.abort(); clearResults(); notice(''); });
$('#query-form').addEventListener('submit', async event => {
  event.preventDefault();
  const query = $('#query').value.trim();
  if (!query || state.busy) return;
  const requestMode = state.mode;
  const controller = new AbortController();
  state.queryController = controller;
  queryBusy(true);
  clearResults();
  $('#research-empty').hidden = true;
  notice('Searching your selected documents…');
  try {
    const response = await api(`/api/v1/${requestMode}`, { method: 'POST', controller, body: {
      query, documentId: $('#document-scope').value || null, topK: Number($('#top-k').value), similarityThreshold: Number($('#threshold').value),
      rewriteQuery: $('#rewrite').checked, retrievalMode: $('#retrieval-mode').value,
    } });
    if (controller.signal.aborted) return;
    const result = requestMode === 'search' ? response.data : response;
    notice('');
    $('#answer-section').hidden = false;
    $('#answer-heading').textContent = requestMode === 'search' ? 'Search results' : 'Your answer';
    $('#answer-status').textContent = requestMode === 'search' ? 'RETRIEVED PASSAGES' : result.abstained ? 'INSUFFICIENT EVIDENCE' : result.cache?.status.endsWith('_HIT') ? 'CACHED · CITATIONS CHECKED' : 'CITATIONS CHECKED';
    $('#asked-query').textContent = query;
    renderSources(result.matches);
    renderAnswer(requestMode === 'search' ? result.matches.length ? `Found ${result.matches.length} relevant passages. Explore the evidence below.` : 'No matching passages in this scope. Try a different term or add a relevant document.' : result.answer, result.matches);
    renderTrace(result.diagnostics, result.cache, result.tokenUsage);
  } catch (error) {
    if (error.name !== 'AbortError') { notice(error.message, true); $('#research-empty').hidden = false; }
  } finally {
    if (state.queryController === controller) { state.queryController = null; queryBusy(false); }
  }
});
window.addEventListener('hashchange', route);

async function start() {
  route();
  const token = new URLSearchParams(location.search).get('token');
  if (location.pathname === '/verify' && token) {
    history.replaceState(null, '', '/');
    try {
      const result = await api(`/docmind/auth/verify/email?token_id=${encodeURIComponent(token)}`, { authenticated: false });
      authMessage(result.message, true);
    } catch (error) { if (error.name !== 'AbortError') authMessage(error.message); }
  }
  if (state.token) {
    try { await enterWorkspace(); }
    catch (error) { if (error.name !== 'AbortError') signOut(error.message); }
  }
}
start();
