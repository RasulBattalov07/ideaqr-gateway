/* ============================================================
   IDEAQR Digital Gateway — Stage 3 SPA
   Vanilla JS. JSON API + Spring Security session.
   All user-facing text is Russian. Official state-portal UI.
   ============================================================ */

(() => {
    'use strict';

    // Apply the persisted theme as early as possible. CSP (script-src 'self') forbids an
    // inline <head> script, so this deferred module is the earliest safe entry point.
    try {
        if (localStorage.getItem('ideaqr_theme') === 'light') {
            document.documentElement.setAttribute('data-theme', 'light');
        }
    } catch (_) { /* ignore */ }

    // -------------------------------------------------------------
    //  State
    // -------------------------------------------------------------
    let currentUser = null;
    let html5QrInstance = null;
    let adminTab = 'manage';
    let citizenTab = 'home';
    let dossierInfo = null;    // мой цифровой пакет (медкарта/досье/визитка) — грузится для дашборда
    let sessionInfo = null;
    let notifList = [];
    let complaintPrefill = null;   // interactionUid pre-selected when filing from history
    let pendingScanTarget = null;  // identifier from a /s/<id> deep link or a guest conversion
    let profilePollTimer = null;   // polls for the owner's confirmation after a profile scan
    let accessPollTimer = null;    // auto-refreshes incoming owner/patient approvals (audit FB-1)
    let accessPollTick = 0;
    let lastAccessSig = null;      // fingerprint of the rendered access-request list (skip no-op repaints)
    let servicesPollTimer = null;  // live two-way service-order flow: repaints «Мои заявки» / очередь исполнителя
    let servicesOrdersSig = '';    // fingerprints, same anti-repaint trick as the access poll
    let servicesQueueSig = '';

    // Demo tooling. Manual UID entry + quick-scenario chips are exposed to EVERY logged-in user
    // for the live presentation (DEMO_MODE = true). The "time machine" is the deliberate exception:
    // it drives the ROLE_ADMIN-only /api/v2/dev/time endpoint (SecurityConfig + @PreAuthorize), so it
    // stays admin-only in renderTimeMachine() to avoid 403s for non-admins. Set DEMO_MODE = false to
    // hide the manual tools again in a real deployment.
    const DEMO_MODE = true;
    function demoToolsEnabled() { return DEMO_MODE || !!(currentUser && currentUser.admin); }

    const app = () => document.getElementById('app');

    // -------------------------------------------------------------
    //  Utilities
    // -------------------------------------------------------------
    function esc(value) {
        if (value === null || value === undefined) return '';
        return String(value)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    // Defence-in-depth for hrefs (audit L-1): only allow http(s) links, so a stored
    // `javascript:`/`data:` URL can never execute when rendered into an anchor.
    function safeUrl(value) {
        if (!value) return '';
        const v = String(value).trim();
        return /^https?:\/\//i.test(v) ? v : '';
    }

    function shortId(uid) {
        if (!uid) return '—';
        return String(uid).replace(/-/g, '').substring(0, 8).toUpperCase();
    }

    // A platform QR encodes an absolute deep link (<origin>/s/<identifier>) so a phone's stock
    // camera can open the app. The in-app reader, however, hands back that whole URL — so peel it
    // back to the bare identifier before scanning, otherwise the gateway looks up the URL string,
    // misses, and wrongly reports «Объект не найден в реестре» for a perfectly valid fresh object
    // or personal IDENTITY: QR. Bare identifiers pass through unchanged.
    function normalizeScanInput(text) {
        let v = String(text === null || text === undefined ? '' : text).trim();
        const i = v.indexOf('/s/');
        if (i >= 0 && /^https?:\/\//i.test(v)) {
            v = v.slice(i + 3);
            const q = v.search(/[?#]/);
            if (q >= 0) v = v.slice(0, q);
            try { v = decodeURIComponent(v); } catch (_) { /* keep the raw path segment */ }
        }
        return v.trim();
    }

    function fmtPrice(value, currency) {
        const n = Number(value || 0);
        return n.toLocaleString('ru-RU') + ' ' + (currency || '₸');
    }

    function stars(rating) {
        const full = Math.round(Number(rating));
        let s = '';
        for (let i = 0; i < 5; i++) s += i < full ? '★' : '☆';
        return s;
    }

    function toast(message, kind = 'info') {
        const box = document.getElementById('toasts');
        const t = document.createElement('div');
        t.className = 'toast ' + kind;
        const ico = kind === 'ok' ? '✓' : kind === 'err' ? '✕' : 'ℹ';
        t.innerHTML = `<span class="t-ico">${ico}</span><span>${esc(message)}</span>`;
        box.appendChild(t);
        setTimeout(() => {
            t.style.transition = 'opacity .3s ease';
            t.style.opacity = '0';
            setTimeout(() => t.remove(), 300);
        }, 4200);
    }

    function inlineLoad(text) {
        return `<div class="inline-load"><span class="il-dot"></span><span>${esc(text)}</span></div>`;
    }

    // -------------------------------------------------------------
    //  Modal dialogs — accessible replacements for native
    //  prompt()/alert()/confirm() (audit 2.1): focus trap, Esc to
    //  close, focus restored to the trigger, role="dialog".
    // -------------------------------------------------------------
    function focusables(root) {
        return Array.from(root.querySelectorAll(
            'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
        )).filter(el => !el.disabled && el.offsetParent !== null);
    }

    function showModal({ title, bodyHtml = '', actions = [], onBody, dismissValue = null, dismissible = true }) {
        return new Promise((resolve) => {
            const prevFocus = document.activeElement;
            const overlay = document.createElement('div');
            overlay.className = 'modal-overlay';
            overlay.innerHTML = `
                <div class="modal-card" role="dialog" aria-modal="true" aria-label="${esc(title)}">
                    <div class="modal-head">
                        <h3>${esc(title)}</h3>
                        ${dismissible ? '<button class="modal-x" type="button" aria-label="Закрыть">✕</button>' : ''}
                    </div>
                    <div class="modal-body">${bodyHtml}</div>
                    <div class="modal-actions"></div>
                </div>`;
            const card = overlay.querySelector('.modal-card');
            const actionsBox = overlay.querySelector('.modal-actions');
            let settled = false;

            function close(result) {
                if (settled) return;
                settled = true;
                document.removeEventListener('keydown', onKey, true);
                overlay.remove();
                if (prevFocus && prevFocus.focus) { try { prevFocus.focus(); } catch (_) { /* ignore */ } }
                resolve(result);
            }
            function onKey(e) {
                if (e.key === 'Escape' && dismissible) { e.preventDefault(); close(dismissValue); return; }
                if (e.key !== 'Tab') return;
                const items = focusables(card);
                if (!items.length) return;
                const first = items[0], last = items[items.length - 1];
                if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
                else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
            }

            actions.forEach(a => {
                const b = document.createElement('button');
                b.type = 'button';
                b.className = 'btn ' + (a.cls || 'btn-ghost');
                b.textContent = a.label;
                b.addEventListener('click', () => close(a.value));
                actionsBox.appendChild(b);
            });
            const xBtn = overlay.querySelector('.modal-x');
            if (xBtn) xBtn.addEventListener('click', () => close(dismissValue));
            if (dismissible) {
                overlay.addEventListener('mousedown', (e) => { if (e.target === overlay) close(dismissValue); });
            }
            document.addEventListener('keydown', onKey, true);
            document.body.appendChild(overlay);
            if (onBody) onBody(card, { close });

            const items = focusables(card);
            const preferred = card.querySelector('[data-autofocus]')
                || items.find(i => !i.classList.contains('modal-x')) || items[0];
            if (preferred) preferred.focus();
        });
    }

    function modalConfirm(message, { title = 'Подтверждение', confirmText = 'Подтвердить', danger = false } = {}) {
        return showModal({
            title,
            bodyHtml: `<p class="modal-text">${esc(message)}</p>`,
            dismissValue: false,
            actions: [
                { label: 'Отмена', cls: 'btn-ghost', value: false },
                { label: confirmText, cls: danger ? 'btn-danger' : 'btn-primary', value: true }
            ]
        });
    }

    function modalAlert(message, { title = 'Сообщение', copyText = null } = {}) {
        const copyBlock = copyText
            ? `<div class="modal-copy"><code class="modal-copy-val">${esc(copyText)}</code>
                 <button class="btn btn-sm btn-ghost modal-copy-btn" type="button">Копировать</button></div>`
            : '';
        return showModal({
            title,
            bodyHtml: `<p class="modal-text">${esc(message)}</p>${copyBlock}`,
            dismissValue: true,
            actions: [{ label: 'Закрыть', cls: 'btn-primary', value: true }],
            onBody: (card) => {
                const btn = card.querySelector('.modal-copy-btn');
                if (btn) btn.addEventListener('click', async () => {
                    try { await navigator.clipboard.writeText(copyText); btn.textContent = 'Скопировано ✓'; }
                    catch (_) { btn.textContent = 'Скопируйте вручную'; }
                });
            }
        });
    }

    function modalSelect(message, options, { title = 'Выбор' } = {}) {
        const opts = options.map((o, i) =>
            `<button class="modal-option" type="button" data-i="${i}">${esc(o.label)}</button>`).join('');
        return showModal({
            title,
            bodyHtml: `<p class="modal-text">${esc(message)}</p><div class="modal-options">${opts}</div>`,
            dismissValue: null,
            actions: [{ label: 'Отмена', cls: 'btn-ghost', value: null }],
            onBody: (card, { close }) => {
                card.querySelectorAll('.modal-option').forEach(btn => {
                    btn.addEventListener('click', () => close(options[Number(btn.dataset.i)].value));
                });
            }
        });
    }

    // Self-service password change (audit 1.7); when `forced` (after an admin reset,
    // audit 4.9) the dialog cannot be dismissed until a new password is set.
    function openChangePasswordModal({ forced = false } = {}) {
        const intro = forced
            ? '<p class="modal-text">Ваш пароль был сброшен администратором. Задайте новый пароль для продолжения.</p>'
            : '';
        const form = `
            <form id="cpw-form" class="form-grid" style="gap:12px">
                <div class="field">
                    <label for="cpw-cur">Текущий пароль</label>
                    <input id="cpw-cur" type="password" autocomplete="current-password">
                </div>
                <div class="field">
                    <label for="cpw-new">Новый пароль</label>
                    <input id="cpw-new" type="password" autocomplete="new-password" placeholder="Не менее 12 символов, буквы и цифры">
                </div>
                <div class="field">
                    <label for="cpw-rep">Повторите новый пароль</label>
                    <input id="cpw-rep" type="password" autocomplete="new-password">
                </div>
                <div class="field-error" id="cpw-err"></div>
                <button class="btn btn-primary btn-block" id="cpw-submit" type="submit" data-autofocus>Сменить пароль</button>
            </form>`;
        return showModal({
            title: forced ? 'Требуется смена пароля' : 'Смена пароля',
            bodyHtml: intro + form,
            dismissible: !forced,
            dismissValue: false,
            actions: forced ? [] : [{ label: 'Отмена', cls: 'btn-ghost', value: false }],
            onBody: (card, { close }) => {
                const formEl = card.querySelector('#cpw-form');
                formEl.addEventListener('submit', async (e) => {
                    e.preventDefault();
                    const cur = card.querySelector('#cpw-cur').value;
                    const nw = card.querySelector('#cpw-new').value;
                    const rep = card.querySelector('#cpw-rep').value;
                    const err = card.querySelector('#cpw-err');
                    err.textContent = '';
                    if (nw !== rep) { err.textContent = 'Новые пароли не совпадают.'; return; }
                    if (nw.length < 12 || !/\d/.test(nw) || !/[A-Za-zА-Яа-яЁё]/.test(nw)) {
                        err.textContent = 'Пароль: не менее 12 символов, буквы и цифры.'; return;
                    }
                    const btn = card.querySelector('#cpw-submit');
                    btn.disabled = true; btn.textContent = 'Сохранение…';
                    const { ok, data } = await apiJson('/api/auth/change-password',
                        { method: 'POST', body: { currentPassword: cur, newPassword: nw } });
                    if (!ok) {
                        err.textContent = (data && data.message)
                            || (data && data.details && Object.values(data.details)[0])
                            || 'Не удалось сменить пароль.';
                        btn.disabled = false; btn.textContent = 'Сменить пароль';
                        return;
                    }
                    toast('Пароль изменён.', 'ok');
                    close(true);
                });
            }
        });
    }

    // After login / on boot: if the account must change its password, block the UI
    // with the forced dialog until it's done, then refresh the profile.
    let pwModalOpen = false;
    async function enforcePasswordChange() {
        if (pwModalOpen || !currentUser || !currentUser.mustChangePassword) return;
        pwModalOpen = true;
        const changed = await openChangePasswordModal({ forced: true });
        pwModalOpen = false;
        if (changed) { await loadMe(); route(); }
    }

    // Audit event labels + status class
    const EVENT_RU = {
        ACCESS_GRANTED: 'Доступ предоставлен',
        ACCESS_DENIED: 'Доступ заблокирован',
        ACCESS_REVIEW: 'Отправлено на проверку',
        QR_CREATED: 'QR-код создан',
        ISSUE_REPORTED: 'Обращение зарегистрировано',
        IDENTITY_CREATED: 'Личность создана',
        IDENTITY_VERIFIED: 'Личность подтверждена',
        USER_REGISTERED: 'Регистрация пользователя',
        WORKING_MODE_ACTIVATED: 'Рабочий режим включён',
        WORKING_MODE_DEACTIVATED: 'Рабочий режим завершён',
        SOS_CREATED: 'SOS-запрос создан',
        GUEST_CREATED: 'Гостевой доступ создан',
        GUEST_MERGED: 'История гостя перенесена',
        NOTIFICATION_CREATED: 'Уведомление'
    };

    function eventTag(evt) {
        const e = (evt || '').toUpperCase();
        if (e.includes('GRANTED')) return '<span class="atag ok">Разрешено</span>';
        if (e.includes('DENIED') || e.includes('REJECTED')) return '<span class="atag bad">Запрещено</span>';
        if (e.includes('REVIEW')) return '<span class="atag review">На проверке</span>';
        return '<span class="atag info">Информация</span>';
    }

    function categoryLabel(category) {
        const labels = {
            MEDICAL: 'Медицинская карта', RETAIL: 'Товар / коммерция', ECO: 'Экологический объект',
            INFRASTRUCTURE: 'Инфраструктурный объект', GENERAL: 'Общий объект',
            LEGAL: 'Правовое досье', UNKNOWN: 'Неизвестно'
        };
        return labels[category] || category;
    }

    const PROF_RU = {
        DOCTOR: 'Врач', PHARMACIST: 'Фармацевт', POLICE: 'Сотрудник полиции',
        INSPECTOR: 'Инспектор инфраструктуры', SELLER: 'Продавец',
        SERVICE_OPERATOR: 'Оператор услуг', RETAIL_ADMIN: 'Администратор торговли',
        CITIZEN: 'Гражданин'
    };
    function professionRu(key) { return PROF_RU[key] || 'Гражданин'; }

    // Server-side pagination controls (audit 3.1). `pageData` is the PageResponse
    // envelope { content, page, size, totalElements, totalPages, hasNext, hasPrevious }.
    function pagerHtml(pageData) {
        if (!pageData || pageData.totalPages <= 1) return '';
        return `<div class="pager">
            <button class="btn btn-sm btn-ghost pager-prev" type="button" ${pageData.hasPrevious ? '' : 'disabled'}>← Назад</button>
            <span class="pager-info">Стр. ${pageData.page + 1} из ${pageData.totalPages} · всего ${pageData.totalElements}</span>
            <button class="btn btn-sm btn-ghost pager-next" type="button" ${pageData.hasNext ? '' : 'disabled'}>Вперёд →</button>
        </div>`;
    }

    function bindPager(scope, pageData, onGo) {
        if (!scope) return;
        const prev = scope.querySelector('.pager-prev');
        const next = scope.querySelector('.pager-next');
        if (prev) prev.addEventListener('click', () => onGo(pageData.page - 1));
        if (next) next.addEventListener('click', () => onGo(pageData.page + 1));
    }

    // -------------------------------------------------------------
    //  API helpers
    // -------------------------------------------------------------
    function getCookie(name) {
        const match = document.cookie.match('(^|;)\\s*' + name + '\\s*=\\s*([^;]+)');
        return match ? decodeURIComponent(match.pop()) : null;
    }

    // CSRF (audit 4.7): the server issues an XSRF-TOKEN cookie; echo it back in the
    // X-XSRF-TOKEN header on every state-changing request so cookie-session POSTs
    // cannot be forged cross-site.
    function withCsrf(headers, method) {
        if (method && method.toUpperCase() !== 'GET') {
            const token = getCookie('XSRF-TOKEN');
            if (token) headers['X-XSRF-TOKEN'] = token;
        }
        return headers;
    }

    // Centralised handling of a lost session (audit M-5). If a call comes back 401 while
    // we believed we were authenticated (session expired or revoked by an admin), drop the
    // cached user and return to the login screen instead of leaving panels silently broken.
    // A 401 when not logged in (initial /api/auth/me, a failed /login) is the normal case
    // and is ignored.
    function handleUnauthorized() {
        if (!currentUser) return;
        currentUser = null;
        stopAccessPolling();
        const bar = document.getElementById('appbar');
        if (bar) bar.hidden = true;
        // D-1: the session was lost (expired, or revoked by an admin role/password change, which
        // the server now reports as a clean JSON 401). Proactively re-prime the XSRF-TOKEN cookie
        // so the upcoming re-login matches on the FIRST attempt instead of bouncing off a stale
        // token with a 403 Forbidden.
        primeCsrf();
        toast('Сессия истекла. Войдите снова.', 'err');
        renderAuth();
    }

    // Materialise the double-submit CSRF cookie with a cheap GET. On a cold load the
    // XSRF-TOKEN cookie may not exist yet when the first state-changing call (e.g. login)
    // fires, which the server rejects with 403 — the classic "ошибка с первого раза".
    // Priming + a one-shot retry makes the first login succeed on the first attempt.
    async function primeCsrf() {
        try { await fetch('/api/health', { credentials: 'same-origin' }); } catch (_) { /* ignore */ }
    }

    function parseBody(text) {
        if (!text) return null;
        try { return JSON.parse(text); } catch (_) { return { raw: text }; }
    }

    async function apiJson(path, { method = 'GET', body, _retried = false } = {}) {
        const m = (method || 'GET').toUpperCase();
        if (m !== 'GET' && !getCookie('XSRF-TOKEN')) await primeCsrf();
        const opts = { method, credentials: 'same-origin', headers: withCsrf({}, method) };
        if (body !== undefined) {
            opts.headers['Content-Type'] = 'application/json';
            opts.body = JSON.stringify(body);
        }
        const res = await fetch(path, opts);
        if (res.status === 403 && m !== 'GET' && !_retried) {
            await primeCsrf();
            return apiJson(path, { method, body, _retried: true });
        }
        if (res.status === 401) handleUnauthorized();
        return { ok: res.ok, status: res.status, data: parseBody(await res.text()) };
    }

    async function apiForm(path, params, _retried = false) {
        if (!getCookie('XSRF-TOKEN')) await primeCsrf();
        const res = await fetch(path, {
            method: 'POST',
            credentials: 'same-origin',
            headers: withCsrf({ 'Content-Type': 'application/x-www-form-urlencoded' }, 'POST'),
            body: new URLSearchParams(params).toString()
        });
        if (res.status === 403 && !_retried) {
            await primeCsrf();
            return apiForm(path, params, true);
        }
        if (res.status === 401) handleUnauthorized();
        return { ok: res.ok, status: res.status, data: parseBody(await res.text()) };
    }

    async function loadMe() {
        dossierInfo = null; // цифровой пакет привязан к сессии — при смене пользователя перезагружаем
        const { ok, data } = await apiJson('/api/auth/me');
        if (ok && data && data.authenticated) { currentUser = data; return true; }
        currentUser = null;
        return false;
    }

    async function doLogin(username, password) {
        const { ok, data } = await apiForm('/login', { username, password });
        if (!ok) throw new Error((data && data.message) || 'Не удалось выполнить вход');
        await loadMe();
    }

    async function doLogout() {
        try { await apiForm('/logout', {}); } catch (_) { /* ignore */ }
        currentUser = null;
        stopAccessPolling();
        document.getElementById('appbar').hidden = true;
        renderAuth();
    }

    async function doGuest() {
        try {
            const { ok, data } = await apiJson('/api/auth/guest', { method: 'POST', body: {} });
            if (!ok || !data) throw new Error((data && data.message) || 'Не удалось войти как гость');
            currentUser = data;
            // Persist the guest UID + the one-time merge token issued to THIS browser.
            // The token proves session ownership when merging later (audit 4.6).
            try {
                localStorage.setItem('ideaqr_guest_uid', data.identityUid);
                if (data.mergeToken) localStorage.setItem('ideaqr_guest_token', data.mergeToken);
            } catch (_) { /* ignore */ }
            toast('Вы вошли как гость. Действия будут записаны.', 'info');
            route();
            consumePendingScan();
        } catch (err) {
            toast(err.message, 'err');
        }
    }

    // After a guest registers, transfer the guest history into the new identity.
    async function maybeMergeGuest() {
        let guestUid = null, guestToken = null;
        try {
            guestUid = localStorage.getItem('ideaqr_guest_uid');
            guestToken = localStorage.getItem('ideaqr_guest_token');
        } catch (_) { /* ignore */ }
        if (!guestUid || !guestToken || !currentUser || guestUid === currentUser.identityUid) return;
        try {
            const { ok, data } = await apiJson('/api/v2/guest/merge',
                { method: 'POST', body: { guestIdentityUid: guestUid, mergeToken: guestToken } });
            if (ok) toast((data && data.message) || 'История гостя перенесена.', 'ok');
        } catch (_) { /* ignore */ }
        try {
            localStorage.removeItem('ideaqr_guest_uid');
            localStorage.removeItem('ideaqr_guest_token');
        } catch (_) { /* ignore */ }
    }

    // Public employer directory shared by BOTH registration forms (audit FB-3: the guest
    // modal offered «Трудоустроен(а)» but no employer picker, so the claim raised nothing).
    // Fetched once per page load; a failed fetch stays uncached so the next toggle retries.
    let orgListCache = null;
    async function fetchOrganizations() {
        if (orgListCache) return orgListCache;
        try {
            const { ok, data } = await apiJson('/api/auth/organizations');
            if (ok && Array.isArray(data) && data.length) orgListCache = data;
        } catch (_) { /* keep null */ }
        return orgListCache || [];
    }

    async function fillOrganizationSelect(sel) {
        if (!sel || sel.options.length > 1) return; // already populated — keep the user's choice
        const orgs = await fetchOrganizations();
        if (!orgs.length) return;
        sel.innerHTML = '<option value="">Выберите компанию…</option>'
            + orgs.map(o => `<option value="${esc(o.organizationUid)}">${esc(o.name)}</option>`).join('');
    }

    // -------------------------------------------------------------
    //  Health pill
    // -------------------------------------------------------------
    async function checkHealth() {
        const dot = document.getElementById('healthDot');
        const txt = document.getElementById('healthText');
        try {
            const { ok, data } = await apiJson('/api/health');
            const up = ok && data && ((data.details && data.details.status === 'UP') || data.success);
            if (!up) throw new Error();
            dot.className = 'gov-dot live';
            txt.textContent = 'система работает';
        } catch (_) {
            dot.className = 'gov-dot down';
            txt.textContent = 'нет связи со шлюзом';
        }
    }

    // -------------------------------------------------------------
    //  Theme (dark default · light opt-in, persisted per browser)
    // -------------------------------------------------------------
    function currentTheme() {
        return document.documentElement.getAttribute('data-theme') === 'light' ? 'light' : 'dark';
    }
    function syncThemeIcon() {
        const ico = document.getElementById('themeIco');
        if (ico) ico.textContent = currentTheme() === 'light' ? '☾' : '☀';
    }
    function applyTheme(theme) {
        if (theme === 'light') document.documentElement.setAttribute('data-theme', 'light');
        else document.documentElement.removeAttribute('data-theme');
        try { localStorage.setItem('ideaqr_theme', theme); } catch (_) { /* ignore */ }
        syncThemeIcon();
    }
    function toggleTheme() { applyTheme(currentTheme() === 'light' ? 'dark' : 'light'); }

    // -------------------------------------------------------------
    //  Routing
    // -------------------------------------------------------------
    function route() {
        renderTimeMachine();
        if (!currentUser || currentUser.admin) stopAccessPolling();
        if (!currentUser) {
            document.getElementById('appbar').hidden = true;
            // A visitor who arrived by scanning a platform QR (a /s/<id> deep link) meets a
            // styled Welcome page that explains the platform and invites them in — instead of
            // being dumped onto a bare login form (Point 1). Everyone else sees the auth screen.
            if (pendingScanTarget) renderGuestWelcome();
            else renderAuth();
            return;
        }
        showAppbar();
        if (currentUser.admin) renderAdmin();
        else renderCitizen();
    }

    function showAppbar() {
        const bar = document.getElementById('appbar');
        bar.hidden = false;
        const chip = document.getElementById('user-chip');
        const initials = ((currentUser.firstName || '')[0] || '') + ((currentUser.lastName || '')[0] || '');
        chip.innerHTML = `
            <div class="uc-meta">
                <div class="uc-name">${esc(currentUser.firstName)} ${esc(currentUser.lastName)}</div>
                <div class="uc-role">${esc(currentUser.professionLabel)}</div>
            </div>
            <div class="uc-avatar">${esc(initials.toUpperCase())}</div>
            ${currentUser.guest ? '' : '<button class="btn btn-ghost btn-sm" id="change-pw-btn" type="button">Сменить пароль</button>'}
            <button class="btn btn-danger btn-sm" id="logout-btn" type="button">Выйти</button>`;
        document.getElementById('logout-btn').addEventListener('click', doLogout);
        const cpwBtn = document.getElementById('change-pw-btn');
        if (cpwBtn) cpwBtn.addEventListener('click', () => openChangePasswordModal());
    }

    // =============================================================
    //  GUEST WELCOME (Point 1) — shown when a visitor lands by scanning a
    //  platform QR while signed out. A real product greets them; it does not
    //  drop them on a bare login form.
    // =============================================================
    function renderGuestWelcome() {
        const scanned = pendingScanTarget || '';
        const isIdentity = scanned.toUpperCase().startsWith('IDENTITY:');
        const scannedLabel = isIdentity ? 'Цифровая личность' : scanned;
        app().innerHTML = `
        <div class="welcome-wrap fade-in">
            <section class="welcome-card">
                <div class="welcome-glow" aria-hidden="true"></div>
                <span class="welcome-badge">▦ Цифровой шлюз · умный доступ</span>
                <div class="welcome-scan">
                    <span class="ws-ico">📷</span>
                    <div class="ws-txt">
                        <strong>Вы отсканировали ${isIdentity ? 'цифровую личность' : 'объект'}</strong>
                        <span>Чтобы открыть содержимое и пройти проверку доступа — войдите или создайте аккаунт.</span>
                    </div>
                    <code class="ws-code">${esc(scannedLabel)}</code>
                </div>
                <h1>Добро пожаловать в <span class="accent">IDEAQR</span></h1>
                <p class="welcome-lead">Это платформа умного доступа. QR-код — лишь ключ-идентификатор:
                    сами данные остаются в защищённых реестрах, а каждый доступ проходит через движок
                    политик и навсегда фиксируется в неизменяемом журнале.</p>
                <div class="welcome-pillars">
                    <div class="welcome-pillar">
                        <div class="wp-ico">🔐</div>
                        <div class="wp-h">Данные под контролем</div>
                        <div class="wp-s">Вы видите ровно то, что разрешено вашей ролью и контекстом</div>
                    </div>
                    <div class="welcome-pillar">
                        <div class="wp-ico">⚖️</div>
                        <div class="wp-h">Каждый доступ — решение</div>
                        <div class="wp-s">Роль, доверие, время и риск проверяются на лету</div>
                    </div>
                    <div class="welcome-pillar">
                        <div class="wp-ico">📜</div>
                        <div class="wp-h">Полная прослеживаемость</div>
                        <div class="wp-s">Журнал только дополняется — действия нельзя подделать</div>
                    </div>
                </div>
                <div class="welcome-actions">
                    <button class="btn btn-primary btn-block" id="wel-register" type="button">Зарегистрироваться и открыть</button>
                    <div class="welcome-sub">
                        <button class="btn btn-ghost" id="wel-login" type="button">У меня есть аккаунт</button>
                        <button class="btn btn-ghost" id="wel-guest" type="button">Продолжить как гость</button>
                    </div>
                </div>
                <p class="welcome-foot">Продолжая, вы соглашаетесь с тем, что действия в системе фиксируются в журнале доступа.</p>
            </section>
        </div>`;
        document.getElementById('wel-register').addEventListener('click', () => renderAuth('register'));
        document.getElementById('wel-login').addEventListener('click', () => renderAuth('login'));
        document.getElementById('wel-guest').addEventListener('click', doGuest);
    }

    // =============================================================
    //  AUTH VIEW
    // =============================================================
    function renderAuth(initialTab) {
        app().innerHTML = `
        ${pendingScanTarget ? `<div class="scan-intent">
            <span class="si-ico">📷</span>
            <div class="si-txt"><strong>Вы отсканировали объект</strong>
            <span>Войдите или продолжите как гость — и мы сразу покажем результат проверки доступа.</span></div>
            <code class="si-code">${esc(pendingScanTarget.startsWith('IDENTITY:') ? 'Цифровая личность' : pendingScanTarget)}</code>
        </div>` : ''}
        <div class="auth-wrap fade-in">
            <aside class="auth-hero">
                <div>
                    <span class="hero-badge">▦ Цифровой Казахстан</span>
                    <h1>Управление <span class="accent">доступом</span><br>и неизменяемый аудит</h1>
                    <p class="lead">IDEAQR — слой управления правами доступа поверх государственных и
                    коммерческих реестров. QR-код служит только идентификатором: данные остаются
                    в реестрах, каждый доступ проходит через движок политик и навсегда фиксируется в журнале.</p>
                </div>
                <div class="hero-pillars">
                    <div class="hero-pillar">
                        <div class="hp-ico">🔐</div>
                        <div class="hp-txt"><strong>Минимизация данных</strong>
                        <span>Хранятся только метаданные и связи, а не сами записи</span></div>
                    </div>
                    <div class="hero-pillar">
                        <div class="hp-ico">⚖️</div>
                        <div class="hp-txt"><strong>Движок решений</strong>
                        <span>Роль, тип запроса, время, уровень доверия и риск</span></div>
                    </div>
                    <div class="hero-pillar">
                        <div class="hp-ico">📜</div>
                        <div class="hp-txt"><strong>Неизменяемый журнал</strong>
                        <span>Только добавление — полная прослеживаемость действий</span></div>
                    </div>
                </div>
                <div class="hero-flow">
                    <div class="hf-label">Как работает доступ</div>
                    <div class="pipeline" id="hero-pipeline"></div>
                </div>
            </aside>

            <section class="panel auth-card">
                <div class="tabs">
                    <button class="tab active" id="tab-login" type="button">Вход</button>
                    <button class="tab" id="tab-register" type="button">eGov · по телефону</button>
                </div>

                <form id="login-form" class="form-grid">
                    <div class="field">
                        <label for="li-username">Имя пользователя</label>
                        <input id="li-username" name="username" type="text" autocomplete="username" placeholder="например, doctor">
                    </div>
                    <div class="field">
                        <label for="li-password">Пароль</label>
                        <input id="li-password" name="password" type="password" autocomplete="current-password" placeholder="••••••••">
                    </div>
                    <div class="field-error" id="login-error"></div>
                    <button class="btn btn-primary btn-block" id="login-submit" type="submit">Войти</button>

                    <div class="demo-creds">
                        <div class="dc-label">Демо-доступы — нажмите, чтобы подставить</div>
                        <div class="cred-grid" id="demo-creds"></div>
                    </div>
                </form>

                <form id="register-form" class="form-grid hidden" autocomplete="off">
                    <div class="egov-head">
                        <span class="egov-logo" aria-hidden="true">eGov</span>
                        <div class="egov-head-txt">
                            <strong>Умная регистрация</strong>
                            <span>Только номер телефона — ФИО, ИИН и адрес подтянутся из госбазы (демо-имитация)</span>
                        </div>
                    </div>
                    <div class="field">
                        <label for="eg-phone">Номер телефона</label>
                        <input id="eg-phone" type="tel" inputmode="tel" autocomplete="tel" placeholder="+7 777 777 77 77">
                        <div class="field-hint">Демо-подсказка: номер, оканчивающийся на <b>7</b>, найдёт «Расула Батталова».</div>
                    </div>
                    <div class="field-error" id="eg-error"></div>
                    <button class="btn btn-primary btn-block" id="eg-lookup" type="submit">Найти меня в eGov</button>
                    <div id="eg-result"></div>
                </form>

                <div class="guest-divider"><span>или</span></div>
                <button class="btn btn-ghost btn-block" id="guest-btn" type="button">Продолжить как гость</button>
            </section>
        </div>`;

        // Static demo pipeline on the hero
        animatePipeline(document.getElementById('hero-pipeline'),
            { identityUid: null, requestUid: null, decisionUid: null, interactionUid: null, historyUid: null },
            'APPROVED', true);

        const loginForm = document.getElementById('login-form');
        const registerForm = document.getElementById('register-form');
        const tabLogin = document.getElementById('tab-login');
        const tabRegister = document.getElementById('tab-register');

        function activate(which) {
            const login = which === 'login';
            tabLogin.classList.toggle('active', login);
            tabRegister.classList.toggle('active', !login);
            loginForm.classList.toggle('hidden', !login);
            registerForm.classList.toggle('hidden', login);
        }
        tabLogin.addEventListener('click', () => activate('login'));
        tabRegister.addEventListener('click', () => activate('register'));
        if (initialTab === 'register') activate('register');

        const guestBtn = document.getElementById('guest-btn');
        if (guestBtn) guestBtn.addEventListener('click', doGuest);

        // Demo logins (clickable) — convenience for the live demo. Clicking a chip fills
        // the login form with that account's credentials and switches to the Вход tab.
        const demoAccounts = [
            { role: 'Администратор', user: 'admin', pass: 'Admin123!', badge: 'admin' },
            { role: 'Продавец', user: 'seller', pass: 'Seller123!', badge: 'user' },
            { role: 'Врач', user: 'doctor', pass: 'Doctor123!', badge: 'user' },
            { role: 'Фармацевт', user: 'pharmacist', pass: 'Pharma123!', badge: 'user' },
            { role: 'Полицейский', user: 'police', pass: 'Police123!', badge: 'user' },
            { role: 'Инспектор', user: 'inspector', pass: 'Inspect123!', badge: 'user' },
            { role: 'Гражданин', user: 'citizen', pass: 'Citizen123!', badge: 'user' }
        ];
        const credBox = document.getElementById('demo-creds');
        if (credBox) {
            credBox.innerHTML = demoAccounts.map((a, i) => `
                <button class="cred-chip" type="button" data-i="${i}">
                    <div class="cc-role">${esc(a.role)}</div>
                    <div class="cc-login">${esc(a.user)} · ${esc(a.pass)}</div>
                    <span class="cc-badge ${a.badge}">${a.badge === 'admin' ? 'Админ' : 'Пользователь'}</span>
                </button>`).join('');
            credBox.querySelectorAll('.cred-chip').forEach(chip => {
                chip.addEventListener('click', () => {
                    const a = demoAccounts[Number(chip.dataset.i)];
                    activate('login');
                    document.getElementById('li-username').value = a.user;
                    document.getElementById('li-password').value = a.pass;
                    document.getElementById('login-submit').focus();
                });
            });
        }

        // ---- eGov smart onboarding (Phase 2): телефон → плашка «Это вы?» → профиль ----
        // Публичный путь по-прежнему НИКОГДА не выдаёт привилегированную роль: сервер
        // создаёт CITIZEN + полный цифровой пакет (медкарта, правовой статус, визитка).
        let egPhone = null;
        const egResult = document.getElementById('eg-result');
        const egErr = document.getElementById('eg-error');

        function egovPlate(data) {
            const p = data.person;
            const initials = ((p.firstName || '')[0] || '') + ((p.lastName || '')[0] || '');
            const already = data.alreadyRegistered;
            return `
            <div class="egov-plate fade-in">
                <div class="ep-badge">✓ Найдено в базе eGov <span class="demo-tag">DEMO</span></div>
                <div class="ep-person">
                    <div class="ep-avatar">${esc(initials.toUpperCase())}</div>
                    <div class="ep-info">
                        <div class="ep-name">${esc(p.fullName)}</div>
                        <div class="ep-kv"><span>ИИН</span><code>${esc(p.iin)}</code></div>
                        <div class="ep-kv"><span>Дата рождения</span><b>${esc(p.birthDate)}</b></div>
                        <div class="ep-kv"><span>Адрес</span><b>${esc(p.address)}</b></div>
                        <div class="ep-kv"><span>Телефон</span><b>${esc(data.phoneDisplay)}</b></div>
                    </div>
                </div>
                ${already ? `
                <div class="ep-q">Этот номер уже зарегистрирован — войдите по SMS-коду</div>
                <div class="otp-row">
                    <input id="eg-otp" type="text" inputmode="numeric" maxlength="4" placeholder="Код из SMS" autocomplete="one-time-code">
                    <button class="btn btn-primary" id="eg-otp-login" type="button">Войти</button>
                </div>
                <div class="field-hint">Демо-код: <b>1234</b> (SMS-шлюз в демо не подключён).</div>` : `
                <div class="ep-q">Это вы?</div>
                <div class="ep-actions">
                    <button class="btn btn-gold btn-block" id="eg-confirm" type="button">✓ Да, это я — создать цифровой профиль</button>
                    <button class="btn btn-ghost btn-block" id="eg-not-me" type="button">Это не я</button>
                </div>
                <p class="ep-note">Будут созданы автоматически: цифровая личность, единый QR, медкарта,
                правовой статус и визитка. Вход в дальнейшем — по SMS-коду.</p>`}
            </div>`;
        }

        async function egovLookup() {
            egErr.textContent = '';
            const raw = document.getElementById('eg-phone').value.trim();
            if (!raw) { egErr.textContent = 'Введите номер телефона.'; return; }
            const btn = document.getElementById('eg-lookup');
            btn.disabled = true; btn.textContent = 'Запрашиваем eGov…';
            try {
                const { ok, data } = await apiJson('/api/auth/egov/lookup', { method: 'POST', body: { phone: raw } });
                if (!ok || !data || !data.person) throw new Error((data && data.message) || 'Гражданин не найден.');
                egPhone = data.phone;
                egResult.innerHTML = egovPlate(data);
                wireEgovPlate(data);
            } catch (err) {
                egErr.textContent = err.message;
            } finally {
                btn.disabled = false; btn.textContent = 'Найти меня в eGov';
            }
        }

        function wireEgovPlate(data) {
            const confirm = document.getElementById('eg-confirm');
            if (confirm) confirm.addEventListener('click', async () => {
                confirm.disabled = true; confirm.textContent = 'Создаём цифровой профиль…';
                try {
                    const { ok, data: res } = await apiJson('/api/auth/egov/register',
                        { method: 'POST', body: { phone: egPhone } });
                    if (!ok || !res || !res.user) throw new Error((res && res.message) || 'Не удалось создать профиль.');
                    currentUser = res.user;
                    dossierInfo = null;
                    toast('Личность подтверждена через eGov. Профиль создан!', 'ok');
                    await maybeMergeGuest();
                    route();
                    consumePendingScan();
                } catch (err) {
                    egErr.textContent = err.message;
                    confirm.disabled = false; confirm.textContent = '✓ Да, это я — создать цифровой профиль';
                }
            });
            const notMe = document.getElementById('eg-not-me');
            if (notMe) notMe.addEventListener('click', () => {
                egResult.innerHTML = '';
                const ph = document.getElementById('eg-phone');
                ph.value = ''; ph.focus();
            });
            const otpLogin = document.getElementById('eg-otp-login');
            if (otpLogin) otpLogin.addEventListener('click', async () => {
                const code = (document.getElementById('eg-otp').value || '').trim();
                if (!code) { egErr.textContent = 'Введите SMS-код (демо: 1234).'; return; }
                otpLogin.disabled = true; otpLogin.textContent = 'Проверяем…';
                try {
                    const { ok, data: res } = await apiJson('/api/auth/egov/login',
                        { method: 'POST', body: { phone: egPhone, code } });
                    if (!ok || !res || !res.user) throw new Error((res && res.message) || 'Код неверен.');
                    currentUser = res.user;
                    dossierInfo = null;
                    toast('Вход выполнен. Добро пожаловать!', 'ok');
                    route();
                    enforcePasswordChange();
                    consumePendingScan();
                } catch (err) {
                    egErr.textContent = err.message;
                    otpLogin.disabled = false; otpLogin.textContent = 'Войти';
                }
            });
        }

        registerForm.addEventListener('submit', (e) => { e.preventDefault(); egovLookup(); });

        loginForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            const username = document.getElementById('li-username').value.trim();
            const password = document.getElementById('li-password').value;
            const errEl = document.getElementById('login-error');
            errEl.textContent = '';
            if (!username || !password) { errEl.textContent = 'Введите имя пользователя и пароль.'; return; }
            const btn = document.getElementById('login-submit');
            btn.disabled = true; btn.textContent = 'Проверяем…';
            try {
                await doLogin(username, password);
                toast('Вход выполнен. Добро пожаловать.', 'ok');
                route();
                enforcePasswordChange();
                consumePendingScan();
            } catch (err) {
                errEl.textContent = err.message;
                btn.disabled = false; btn.textContent = 'Войти';
            }
        });

    }

    // =============================================================
    //  ADMIN VIEW
    // =============================================================
    function renderAdmin() {
        app().innerHTML = `
        <div class="fade-in">
            <div class="page-head">
                <div>
                    <h2>Панель управления объектами</h2>
                    <p>Создавайте управляемые QR-коды для товаров и объектов. Каждое создание проходит
                    полный конвейер: Запрос → Решение → QR → Назначение → Действие → История.</p>
                </div>
            </div>
            <div class="view-nav">
                <button data-tab="manage" type="button">Управление</button>
                <button data-tab="users" type="button">Пользователи</button>
                <button data-tab="employment" type="button">Трудоустройство<span id="emp-badge" class="cb-count" hidden>0</span></button>
                <button data-tab="sos" type="button">🆘 Тревоги<span id="sos-badge" class="cb-count" hidden>0</span></button>
                <button data-tab="stats" type="button">Статистика</button>
                <button data-tab="complaints" type="button">Жалобы</button>
                <button data-tab="audit" type="button">Аудит</button>
            </div>
            <div id="admin-body"></div>
        </div>`;

        const nav = app().querySelector('.view-nav');
        nav.querySelectorAll('button').forEach(b => {
            b.classList.toggle('active', b.dataset.tab === adminTab);
            b.addEventListener('click', () => { adminTab = b.dataset.tab; renderAdmin(); });
        });

        switch (adminTab) {
            case 'users': renderAdminUsers(); break;
            case 'employment': renderAdminEmployment(); break;
            case 'sos': renderAdminSos(); break;
            case 'stats': renderAdminStats(); break;
            case 'complaints': renderAdminComplaints(); break;
            case 'audit': renderAdminAudit(); break;
            default: renderAdminManage();
        }
        refreshSosBadge();
        refreshEmploymentBadge();
    }

    function renderAdminManage() {
        document.getElementById('admin-body').innerHTML = `
        <div class="split split-wide">
            <section class="panel panel-pad">
                <div class="section-title">Новый объект</div>
                <form id="create-form" class="form-grid">
                    <div class="field">
                        <label for="cf-category">Тип объекта</label>
                        <select id="cf-category">
                            <option value="RETAIL">Товар / коммерция</option>
                            <option value="ECO">Экологический объект</option>
                            <option value="INFRASTRUCTURE">Инфраструктурный объект</option>
                            <option value="MEDICAL">Медицинская карта</option>
                            <option value="GENERAL">Общий объект</option>
                        </select>
                    </div>
                    <div class="field">
                        <label for="cf-name">Наименование</label>
                        <input id="cf-name" type="text" placeholder="например, Toyota Camry 2024">
                    </div>
                    <div class="field">
                        <label for="cf-desc">Описание</label>
                        <textarea id="cf-desc" placeholder="Краткое описание объекта"></textarea>
                    </div>

                    <div class="cat-fields active" data-cat="RETAIL">
                        <div class="form-row">
                            <div class="field"><label for="cf-brand">Бренд</label>
                                <input id="cf-brand" type="text" placeholder="Adidas"></div>
                            <div class="field"><label for="cf-price">Цена</label>
                                <input id="cf-price" type="number" min="0" placeholder="25000"></div>
                        </div>
                        <div class="field" style="max-width:200px">
                            <label for="cf-currency">Валюта</label>
                            <input id="cf-currency" type="text" value="₸" placeholder="₸">
                        </div>
                        <div>
                            <div class="section-title" style="margin-top:4px">Размеры и остатки</div>
                            <div class="dyn-rows" id="sizes-rows"></div>
                            <button class="btn btn-ghost btn-sm dyn-add" id="add-size" type="button">+ Размер</button>
                        </div>
                        <div>
                            <div class="section-title" style="margin-top:4px">Где купить дешевле</div>
                            <div class="dyn-rows" id="alts-rows"></div>
                            <button class="btn btn-ghost btn-sm dyn-add" id="add-alt" type="button">+ Альтернатива</button>
                        </div>
                        <div class="form-row">
                            <div class="field"><label for="cf-promo">Промокод лояльности</label>
                                <input id="cf-promo" type="text" placeholder="IDEAQR-ADIDAS-10"></div>
                            <div class="field"><label for="cf-promo-note">Описание промокода</label>
                                <input id="cf-promo-note" type="text" placeholder="Скидка 10% в фирменном магазине"></div>
                        </div>
                    </div>

                    <div class="cat-fields" data-cat="ECO">
                        <div class="field"><label for="cf-location-eco">Местоположение</label>
                            <input id="cf-location-eco" type="text" placeholder="г. Астана, ул. ..."></div>
                    </div>
                    <div class="cat-fields" data-cat="INFRASTRUCTURE">
                        <div class="field"><label for="cf-location-infra">Местоположение</label>
                            <input id="cf-location-infra" type="text" placeholder="г. Астана, район ..."></div>
                    </div>

                    <div class="field-error" id="create-error"></div>
                    <button class="btn btn-primary btn-block" id="create-submit" type="submit">
                        Сгенерировать управляемый QR-код
                    </button>
                </form>
            </section>

            <section>
                <div class="panel panel-pad" id="admin-output">
                    <div class="placeholder-box">
                        <div class="pb-ico">▦</div>
                        <div class="pb-title">QR-код появится здесь</div>
                        <div class="pb-sub">Заполните форму и запустите конвейер создания</div>
                    </div>
                </div>
                <div class="panel panel-pad mt-md">
                    <div class="section-title">Созданные объекты</div>
                    <div id="obj-list" class="obj-list">${inlineLoad('Загрузка…')}</div>
                </div>
            </section>
        </div>`;

        wireAdminForm();
        loadAdminObjects();
    }

    function wireAdminForm() {
        const categorySel = document.getElementById('cf-category');
        const groups = Array.from(document.querySelectorAll('.cat-fields'));
        function syncCategory() {
            const cat = categorySel.value;
            groups.forEach(g => g.classList.toggle('active', g.dataset.cat === cat));
        }
        categorySel.addEventListener('change', syncCategory);
        syncCategory();

        const sizesRows = document.getElementById('sizes-rows');
        function addSizeRow(size = '', stock = '') {
            const row = document.createElement('div');
            row.className = 'dyn-row sizes';
            row.innerHTML = `
                <input type="text" placeholder="Размер (S, M, L)" value="${esc(size)}" data-k="size">
                <input type="number" min="0" placeholder="Остаток" value="${esc(stock)}" data-k="stock">
                <button class="dyn-del" type="button" aria-label="Удалить">✕</button>`;
            row.querySelector('.dyn-del').addEventListener('click', () => row.remove());
            sizesRows.appendChild(row);
        }
        document.getElementById('add-size').addEventListener('click', () => addSizeRow());
        addSizeRow('S', '5'); addSizeRow('M', '12'); addSizeRow('L', '0');

        const altsRows = document.getElementById('alts-rows');
        function addAltRow(store = '', price = '', url = '', note = '') {
            const row = document.createElement('div');
            row.className = 'dyn-row alts';
            row.innerHTML = `
                <input type="text" placeholder="Магазин" value="${esc(store)}" data-k="store">
                <input type="number" min="0" placeholder="Цена" value="${esc(price)}" data-k="price">
                <input type="text" placeholder="Ссылка" value="${esc(url)}" data-k="url">
                <input type="text" placeholder="Примечание" value="${esc(note)}" data-k="note">
                <button class="dyn-del" type="button" aria-label="Удалить">✕</button>`;
            row.querySelector('.dyn-del').addEventListener('click', () => row.remove());
            altsRows.appendChild(row);
        }
        document.getElementById('add-alt').addEventListener('click', () => addAltRow());
        addAltRow('Kaspi Магазин', '22990', 'https://kaspi.kz', 'Дешевле, доставка 1–2 дня');

        document.getElementById('create-form').addEventListener('submit', async (e) => {
            e.preventDefault();
            const errEl = document.getElementById('create-error');
            errEl.textContent = '';
            const category = categorySel.value;
            const displayName = document.getElementById('cf-name').value.trim();
            if (!displayName) { errEl.textContent = 'Укажите наименование объекта.'; return; }

            const payload = { category, displayName, description: document.getElementById('cf-desc').value.trim() };
            if (category === 'RETAIL') {
                payload.brand = document.getElementById('cf-brand').value.trim();
                const priceVal = document.getElementById('cf-price').value;
                payload.price = priceVal ? parseInt(priceVal, 10) : null;
                payload.currency = document.getElementById('cf-currency').value.trim() || '₸';
                payload.discountCode = document.getElementById('cf-promo').value.trim();
                payload.discountNote = document.getElementById('cf-promo-note').value.trim();
                payload.sizes = Array.from(sizesRows.querySelectorAll('.dyn-row')).map(r => ({
                    size: r.querySelector('[data-k="size"]').value.trim(),
                    stock: parseInt(r.querySelector('[data-k="stock"]').value || '0', 10)
                })).filter(s => s.size);
                payload.alternatives = Array.from(altsRows.querySelectorAll('.dyn-row')).map(r => ({
                    store: r.querySelector('[data-k="store"]').value.trim(),
                    price: parseInt(r.querySelector('[data-k="price"]').value || '0', 10),
                    url: r.querySelector('[data-k="url"]').value.trim(),
                    note: r.querySelector('[data-k="note"]').value.trim()
                })).filter(a => a.store);
            } else if (category === 'ECO') {
                payload.location = document.getElementById('cf-location-eco').value.trim();
            } else if (category === 'INFRASTRUCTURE') {
                payload.location = document.getElementById('cf-location-infra').value.trim();
            }

            const btn = document.getElementById('create-submit');
            btn.disabled = true; btn.textContent = 'Обработка конвейера…';
            const out = document.getElementById('admin-output');
            out.innerHTML = inlineLoad('Запрос проходит через движок решений…');
            try {
                const { ok, data } = await apiJson('/api/admin/qr/create', { method: 'POST', body: payload });
                if (!data || (!ok && !data.outcome)) throw new Error((data && data.message) || 'Ошибка создания объекта');
                renderAdminResult(data);
                if (data.success) { toast('Объект создан, QR-код сгенерирован.', 'ok'); loadAdminObjects(); }
                else toast('Создание отклонено движком политик.', 'err');
            } catch (err) {
                out.innerHTML = `<div class="placeholder-box"><div class="pb-ico">⚠</div>
                    <div class="pb-title">Не удалось создать объект</div>
                    <div class="pb-sub">${esc(err.message)}</div></div>`;
                toast(err.message, 'err');
            } finally {
                btn.disabled = false; btn.textContent = 'Сгенерировать управляемый QR-код';
            }
        });
    }

    function renderAdminResult(data) {
        const out = document.getElementById('admin-output');
        if (!data.success) {
            out.innerHTML = `${verdictHtml('REJECTED', data.reason, null)}<div class="pipeline mt-md" id="admin-pipeline"></div>`;
            animatePipeline(document.getElementById('admin-pipeline'), data, 'REJECTED');
            return;
        }
        out.innerHTML = `
            <div class="section-title">Готово — QR-код объекта</div>
            <div class="qr-result">
                <img src="${esc(data.qrImageDataUri)}" alt="QR-код: ${esc(data.displayName)}">
                <div class="qr-name">${esc(data.displayName)}</div>
                <div class="qr-uid">${esc(data.objectUid)}</div>
                <div class="qr-actions">
                    <a class="btn btn-ghost btn-sm" href="${esc(data.qrImageDataUri)}" download="${esc(data.objectUid)}.png">Скачать PNG</a>
                </div>
            </div>
            <div class="pipeline mt-md" id="admin-pipeline"></div>`;
        animatePipeline(document.getElementById('admin-pipeline'), data, 'APPROVED');
    }

    let adminObjectsPage = 0;
    async function loadAdminObjects(page = adminObjectsPage) {
        adminObjectsPage = page;
        const list = document.getElementById('obj-list');
        if (!list) return;
        try {
            const { ok, data } = await apiJson(`/api/admin/qr/list?page=${page}&size=60`);
            const items = data && Array.isArray(data.content) ? data.content : null; // paginated (audit M-2)
            if (!ok || !items) { list.innerHTML = `<div class="obj-empty">Не удалось загрузить список.</div>`; return; }
            if (items.length === 0) { list.innerHTML = `<div class="obj-empty">Пока нет созданных объектов.</div>`; return; }
            list.innerHTML = items.map(o => `
                <div class="obj-item">
                    <img src="${esc(o.qrImageUrl)}" alt="QR ${esc(o.objectUid)}" loading="lazy">
                    <div class="oi-body">
                        <div class="oi-name">${esc(o.displayName)}</div>
                        <div class="oi-uid">${esc(o.objectUid)}</div>
                        <div class="oi-meta">${esc(categoryLabel(o.category))} · Владелец ${esc(shortId(o.ownerIdentityUid))} · ${esc(o.createdAt || '')}</div>
                    </div>
                    <button class="btn btn-ghost btn-sm obj-transfer" type="button"
                        data-uid="${esc(o.objectUid)}" data-owner="${esc(o.ownerIdentityUid || '')}">Передать владельца</button>
                </div>`).join('') + pagerHtml(data);
            list.querySelectorAll('.obj-transfer').forEach(btn => {
                btn.addEventListener('click', () => transferOwner(btn.dataset.uid, btn.dataset.owner));
            });
            bindPager(list, data, p => loadAdminObjects(p));
        } catch (_) {
            list.innerHTML = `<div class="obj-empty">Ошибка загрузки списка.</div>`;
        }
    }

    // -------------------------------------------------------------
    //  Admin: transfer object ownership (Identity → Identity)
    //  Object is not re-created; its history is preserved (OBJECT_TRANSFERRED).
    // -------------------------------------------------------------
    async function transferOwner(objectUid, currentOwnerUid) {
        // Build an owner picker from the admin user list (each row carries identityUid).
        let users = [];
        try {
            const { ok, data } = await apiJson('/api/admin/users?page=0&size=100');
            if (ok && data && Array.isArray(data.content)) users = data.content;
        } catch (_) { /* fall back to manual UID entry */ }
        const picked = await modalTransferOwner(objectUid, users, currentOwnerUid);
        if (!picked) return;
        try {
            const { ok, data } = await apiJson(`/api/admin/objects/${encodeURIComponent(objectUid)}/transfer`,
                { method: 'POST', body: { newOwnerIdentityUid: picked.uid, note: picked.note || undefined } });
            if (!ok) { toast((data && data.message) || 'Не удалось передать объект.', 'err'); return; }
            toast('Объект передан новому владельцу. История сохранена.', 'ok');
            loadAdminObjects();
        } catch (_) { toast('Ошибка передачи объекта.', 'err'); }
    }

    function modalTransferOwner(objectUid, users, currentOwnerUid) {
        const opts = users
            .filter(u => u.identityUid && u.identityUid !== currentOwnerUid)
            .map(u => `<option value="${esc(u.identityUid)}">${esc(u.fullName)} · ${esc(u.username)}</option>`).join('');
        const ownerField = opts
            ? `<select id="to-owner">${opts}</select>`
            : `<input id="to-owner" type="text" placeholder="Identity UID нового владельца">`;
        return showModal({
            title: 'Передать владельца объекта',
            bodyHtml: `
                <p class="modal-text">Объект <code>${esc(objectUid)}</code> будет закреплён за новой
                личностью. Объект не пересоздаётся — передача фиксируется в неизменяемом журнале
                как <strong>OBJECT_TRANSFERRED</strong>, история сохраняется.</p>
                <div class="field" style="margin-top:14px"><label for="to-owner">Новый владелец</label>${ownerField}</div>
                <div class="field" style="margin-top:10px"><label for="to-note">Примечание</label>
                    <input id="to-note" type="text" placeholder="например, продажа / передача"></div>`,
            dismissValue: null,
            onBody: (card, { close }) => {
                const box = card.querySelector('.modal-actions');
                box.innerHTML = `
                    <button class="btn btn-ghost" type="button" id="to-cancel">Отмена</button>
                    <button class="btn btn-primary" type="button" id="to-ok" data-autofocus>Передать владельца</button>`;
                card.querySelector('#to-cancel').addEventListener('click', () => close(null));
                card.querySelector('#to-ok').addEventListener('click', () => {
                    const uid = card.querySelector('#to-owner').value.trim();
                    if (!uid) { toast('Укажите нового владельца.', 'err'); return; }
                    close({ uid, note: card.querySelector('#to-note').value.trim() });
                });
            }
        });
    }

    function renderAdminAudit() {
        document.getElementById('admin-body').innerHTML = `
        <section class="panel panel-pad">
            <div class="audit-head">
                <div class="section-title" style="margin-bottom:0">Неизменяемый журнал системы</div>
                <div class="audit-head-actions">
                    <button class="btn btn-ghost btn-sm" id="audit-verify" type="button">Проверить целостность</button>
                    <button class="btn btn-ghost btn-sm" id="audit-refresh" type="button">Обновить</button>
                </div>
            </div>
            <div class="table-scroll">
                <table class="audit-tbl">
                    <thead><tr><th>Событие</th><th>Статус</th><th>Объект</th><th>ID записи</th><th>Время</th></tr></thead>
                    <tbody id="audit-body"><tr><td colspan="5" class="empty">${'Загрузка…'}</td></tr></tbody>
                </table>
            </div>
            <div id="audit-pager"></div>
        </section>`;
        document.getElementById('audit-refresh').addEventListener('click', () => loadAudit('/api/v2/audit'));
        document.getElementById('audit-verify').addEventListener('click', verifyAuditChain);
        loadAudit('/api/v2/audit');
    }

    // Surface the hash-chain integrity check (audit 4.5) so the "immutable journal"
    // claim is demonstrable, not just asserted.
    async function verifyAuditChain() {
        try {
            const { ok, data } = await apiJson('/api/admin/audit/verify');
            if (!ok || !data) { toast('Не удалось проверить целостность журнала.', 'err'); return; }
            if (data.valid) {
                await modalAlert(`Хэш-цепочка журнала цела. Проверено записей: ${data.entriesChecked}.`,
                    { title: 'Целостность подтверждена' });
            } else {
                await modalAlert(`Обнаружено нарушение цепочки на записи ${data.brokenAtHistoryUid}. `
                    + `Журнал был изменён в обход системы.`, { title: 'Нарушение целостности' });
            }
        } catch (_) { toast('Ошибка проверки целостности.', 'err'); }
    }

    // =============================================================
    //  ADMIN: Users / Statistics / Analytics / Complaints / Modules
    // =============================================================
    let adminUsersPage = 0;

    function renderAdminUsers(page = adminUsersPage) {
        adminUsersPage = page;
        const body = document.getElementById('admin-body');
        body.innerHTML = `<section class="panel panel-pad">${inlineLoad('Загрузка пользователей…')}</section>`;
        apiJson(`/api/admin/users?page=${page}&size=25`).then(({ ok, data }) => {
            if (!ok || !data || !Array.isArray(data.content)) { body.innerHTML = `<section class="panel panel-pad"><div class="obj-empty">Не удалось загрузить.</div></section>`; return; }
            const rows = data.content;
            const me = currentUser ? currentUser.username : null;
            body.innerHTML = `
            <section class="panel panel-pad">
                <div class="section-title">Пользователи системы (${data.totalElements})</div>
                <p class="muted" style="margin-bottom:14px">Блокировка, изменение роли, уровень доступа и сброс пароля. Все пользователи платформы (режим супер-администратора). Заблокированный пользователь не может войти и выполнять запросы.</p>
                <div class="table-scroll"><table class="audit-tbl">
                    <thead><tr><th>Имя</th><th>Логин</th><th>Роль</th><th>Статус</th><th>Доверие</th><th>Риск</th><th>Действия</th></tr></thead>
                    <tbody>${rows.map(u => {
                        const isSelf = me && u.username === me;
                        const specialist = ['DOCTOR', 'INSPECTOR', 'PHARMACIST'].includes(u.profession);
                        const profBadge = `<span class="atag role${specialist ? ' specialist' : ''}">${esc(u.professionLabel)}</span>`;
                        const statusBadge = u.blocked
                            ? `<span class="atag bad"${u.blockedReason ? ` title="Причина: ${esc(u.blockedReason)}"` : ''}>● Заблокирован</span>`
                            : '<span class="atag ok">● Активен</span>';
                        const adminBadge = u.admin ? ' <span class="atag admin">Админ</span>' : '';
                        const blockNote = (u.blocked && (u.blockedReason || u.blockedAt))
                            ? `<div class="um-block-reason">${u.blockedReason ? esc(u.blockedReason) : 'Без указания причины'}${u.blockedAt ? ' · ' + esc(u.blockedAt) : ''}</div>`
                            : '';
                        const blockBtn = u.blocked
                            ? `<button class="btn btn-sm btn-ghost" data-act="unblock" data-u="${esc(u.username)}">Разблокировать</button>`
                            : `<button class="btn btn-sm btn-danger" data-act="block" data-u="${esc(u.username)}" ${isSelf ? 'disabled title="Нельзя заблокировать себя"' : ''}>Заблокировать</button>`;
                        const roleChangeBtn = `<button class="btn btn-sm btn-ghost" data-act="role-change" data-u="${esc(u.username)}" data-prof="${esc(u.profession)}" ${isSelf ? 'disabled title="Нельзя менять свою роль"' : ''}>Изменить роль</button>`;
                        const adminBtn = u.admin
                            ? `<button class="btn btn-sm btn-ghost" data-act="demote" data-u="${esc(u.username)}" ${isSelf ? 'disabled title="Нельзя понизить себя"' : ''}>Снять админа</button>`
                            : `<button class="btn btn-sm btn-gold" data-act="promote" data-u="${esc(u.username)}">Сделать админом</button>`;
                        const pwBtn = `<button class="btn btn-sm btn-ghost" data-act="reset" data-u="${esc(u.username)}">Сброс пароля</button>`;
                        return `
                        <tr>
                            <td data-label="Имя">${esc(u.fullName)}${isSelf ? ' <span class="atag info">вы</span>' : ''}</td>
                            <td class="mono" data-label="Логин">${esc(u.username)}</td>
                            <td data-label="Роль">${profBadge}</td>
                            <td data-label="Статус">${statusBadge}${adminBadge}${blockNote}</td>
                            <td data-label="Доверие"><strong>${esc(u.trustLevel)}</strong> / 100</td>
                            <td data-label="Риск">${esc(u.riskScore || '—')}</td>
                            <td data-label="Действия"><div class="um-actions">${blockBtn}${roleChangeBtn}${adminBtn}${pwBtn}</div></td>
                        </tr>`;
                    }).join('')}</tbody>
                </table></div>
                ${pagerHtml(data)}
            </section>`;
            body.querySelectorAll('.um-actions button[data-act]').forEach(btn => {
                btn.addEventListener('click', () => handleUserAction(
                    btn.getAttribute('data-act'), btn.getAttribute('data-u'), btn.getAttribute('data-prof')));
            });
            bindPager(body, data, p => renderAdminUsers(p));
        });
    }

    async function handleUserAction(act, username, profession) {
        let path;
        let bodyData;

        if (act === 'block') {
            const result = await modalBlockUser(username);
            if (!result.ok) return;
            path = `/api/admin/users/${encodeURIComponent(username)}/block`;
            bodyData = { reason: result.reason || undefined };
        } else if (act === 'role-change') {
            const picked = await modalChangeRole(username, profession);
            if (!picked || picked === profession) return;
            path = `/api/admin/users/${encodeURIComponent(username)}/profession`;
            bodyData = { profession: picked };
        } else {
            const confirms = {
                unblock: `Разблокировать пользователя «${username}»?`,
                promote: `Сделать «${username}» администратором? Доступ к панели управления применится сразу.`,
                demote: `Снять права администратора у «${username}»? Активные сессии будут завершены.`,
                reset: `Сбросить пароль пользователя «${username}»? Будет выдан новый временный пароль, а текущие сессии завершены.`
            };
            if (confirms[act]) {
                const ok = await modalConfirm(confirms[act], { confirmText: 'Подтвердить' });
                if (!ok) return;
            }
            const routes = {
                unblock: { path: `/api/admin/users/${encodeURIComponent(username)}/unblock`, body: undefined },
                promote: { path: `/api/admin/users/${encodeURIComponent(username)}/role`, body: { admin: true } },
                demote: { path: `/api/admin/users/${encodeURIComponent(username)}/role`, body: { admin: false } },
                reset: { path: `/api/admin/users/${encodeURIComponent(username)}/reset-password`, body: undefined }
            };
            const route = routes[act];
            if (!route) return;
            path = route.path;
            bodyData = route.body;
        }

        try {
            const { ok, data } = await apiJson(path, { method: 'POST', body: bodyData });
            if (!ok) { toast((data && data.message) || 'Не удалось выполнить действие.', 'err'); return; }
            if (act === 'reset' && data && data.details && data.details.temporaryPassword) {
                const tmp = data.details.temporaryPassword;
                toast('Пароль сброшен.', 'ok');
                await modalAlert('Временный пароль показывается один раз. Передайте его пользователю.',
                    { title: 'Временный пароль · ' + username, copyText: tmp });
            } else {
                toast((data && data.message) || 'Готово.', 'ok');
            }
            renderAdminUsers();
        } catch (_) {
            toast('Ошибка выполнения действия.', 'err');
        }
    }

    // Block dialog with an optional reason (User Management module).
    function modalBlockUser(username) {
        return showModal({
            title: 'Блокировка пользователя',
            bodyHtml: `
                <p class="modal-text">Заблокировать «${esc(username)}»? Он не сможет войти и выполнять запросы; активные сессии будут немедленно завершены.</p>
                <div class="field" style="margin-top:12px">
                    <label for="block-reason">Причина (необязательно)</label>
                    <input id="block-reason" type="text" maxlength="300" placeholder="например, нарушение правил">
                </div>`,
            dismissValue: { ok: false },
            onBody: (card, { close }) => {
                const box = card.querySelector('.modal-actions');
                box.innerHTML = `
                    <button class="btn btn-ghost" type="button" id="bl-cancel">Отмена</button>
                    <button class="btn btn-danger" type="button" id="bl-ok" data-autofocus>Заблокировать</button>`;
                card.querySelector('#bl-cancel').addEventListener('click', () => close({ ok: false }));
                card.querySelector('#bl-ok').addEventListener('click', () =>
                    close({ ok: true, reason: card.querySelector('#block-reason').value.trim() }));
            }
        });
    }

    // Role / profession change dialog. Specialist and admin roles unlock protected data.
    function modalChangeRole(username, currentProfession) {
        const professions = [
            ['CITIZEN', 'Гражданин'], ['SELLER', 'Продавец'], ['SERVICE_OPERATOR', 'Оператор услуг'],
            ['PHARMACIST', 'Фармацевт'], ['DOCTOR', 'Врач'],
            ['INSPECTOR', 'Инспектор инфраструктуры'], ['RETAIL_ADMIN', 'Администратор торговли']
        ];
        const options = professions.map(([value, label]) =>
            `<option value="${value}" ${value === currentProfession ? 'selected' : ''}>${esc(label)}</option>`).join('');
        return showModal({
            title: 'Изменить роль пользователя',
            bodyHtml: `
                <p class="modal-text">Назначить роль для «${esc(username)}». Роли врача, инспектора и администратора открывают доступ к защищённым данным — назначайте только после проверки.</p>
                <div class="field" style="margin-top:12px">
                    <label for="role-select">Профессия / роль</label>
                    <select id="role-select">${options}</select>
                </div>`,
            dismissValue: null,
            onBody: (card, { close }) => {
                const box = card.querySelector('.modal-actions');
                box.innerHTML = `
                    <button class="btn btn-ghost" type="button" id="rl-cancel">Отмена</button>
                    <button class="btn btn-primary" type="button" id="rl-ok" data-autofocus>Назначить роль</button>`;
                card.querySelector('#rl-cancel').addEventListener('click', () => close(null));
                card.querySelector('#rl-ok').addEventListener('click', () =>
                    close(card.querySelector('#role-select').value));
            }
        });
    }

    function statCard(label, value, ico) {
        return `<div class="stat-card"><div class="stat-ico">${ico}</div>
            <div class="stat-val">${esc(value)}</div><div class="stat-label">${esc(label)}</div></div>`;
    }

    // Statistics + Analytics are now ONE tab (P1): the two old pages overlapped, so they are
    // fetched together and rendered as a single dashboard — no more empty duplicate page.
    function renderAdminStats() {
        const body = document.getElementById('admin-body');
        body.innerHTML = `<section class="panel panel-pad">${inlineLoad('Загрузка статистики и аналитики…')}</section>`;
        Promise.all([apiJson('/api/admin/stats'), apiJson('/api/admin/analytics')]).then(([s, a]) => {
            const data = (s.ok && s.data) ? s.data : null;
            const an = (a.ok && a.data) ? a.data : null;
            if (!data && !an) { body.innerHTML = `<section class="panel panel-pad"><div class="obj-empty">Нет данных.</div></section>`; return; }
            const dist = (an && an.professionDistribution) || {};
            const distRows = Object.keys(dist).map(k =>
                `<tr><td>${esc(k)}</td><td style="text-align:right">${esc(dist[k])}</td></tr>`).join('');
            body.innerHTML = `
            <section class="panel panel-pad">
                <div class="section-title">Статистика платформы</div>
                <div class="stat-grid">
                    ${statCard('Пользователи', data ? data.users : '—', '👤')}
                    ${statCard('Гости', data ? data.guests : '—', '👥')}
                    ${statCard('QR-коды', data ? data.qrCodes : '—', '▦')}
                    ${statCard('Сканирования', data ? data.scans : '—', '📷')}
                    ${statCard('Взаимодействия', data ? data.interactions : '—', '🔗')}
                    ${statCard('Жалобы', data ? data.complaints : '—', '⚠')}
                </div>
            </section>
            <section class="panel panel-pad mt-md">
                <div class="section-title">Аналитика и конверсия</div>
                <div class="stat-grid">
                    ${statCard('Регистрации', an ? an.registeredUsers : '—', '📈')}
                    ${statCard('Гостевые личности', an ? an.guestIdentities : '—', '👥')}
                    ${statCard('Конверсия гостей', an ? an.guestConversions : '—', '🔄')}
                    ${statCard('% конверсии', an ? (an.guestConversionRate || 0) + '%' : '—', '✓')}
                    ${statCard('Просмотры профилей', an ? an.profileViews : '—', '👁')}
                    ${statCard('Запросы доступа', an ? an.accessRequests : '—', '🔐')}
                    ${statCard('Доступ подтверждён', an ? an.accessConfirmed : '—', '🤝')}
                    ${statCard('Всего взаимодействий', an ? an.totalInteractions : '—', '🔗')}
                </div>
                <div class="section-title" style="margin-top:18px">Популярные профили</div>
                <div class="table-scroll"><table class="audit-tbl">
                    <thead><tr><th>Профессия</th><th style="text-align:right">Кол-во</th></tr></thead>
                    <tbody>${distRows || '<tr><td colspan="2" class="empty">Нет данных.</td></tr>'}</tbody>
                </table></div>
            </section>`;
        });
    }

    // SOS alert queue (P0): the SOS button finally means something — every SOS surfaces here
    // for the administrator (across all tenants), who can mark it resolved.
    function renderAdminSos() {
        const body = document.getElementById('admin-body');
        body.innerHTML = `<section class="panel panel-pad">${inlineLoad('Загрузка тревог…')}</section>`;
        apiJson('/api/admin/sos').then(({ ok, data }) => {
            if (!ok || !Array.isArray(data)) { body.innerHTML = `<section class="panel panel-pad"><div class="obj-empty">Не удалось загрузить тревоги.</div></section>`; return; }
            const open = data.filter(s => s.status !== 'RESOLVED');
            body.innerHTML = `
            <section class="panel panel-pad">
                <div class="section-title">🆘 Тревоги (SOS) — активных: ${open.length} из ${data.length}</div>
                <p class="muted" style="margin-bottom:14px">Экстренные запросы со всех организаций. Каждый SOS из терминала попадает сюда — это и есть реальная эскалация. Обработайте и отметьте как решённый.</p>
                ${data.length === 0 ? '<div class="obj-empty">Тревог нет.</div>' : `
                <div class="table-scroll"><table class="audit-tbl">
                    <thead><tr><th>Статус</th><th>От кого</th><th>Сообщение</th><th>Время</th><th></th></tr></thead>
                    <tbody>${data.map(s => `
                        <tr>
                            <td><span class="atag ${s.status === 'RESOLVED' ? 'ok' : 'bad'}">${s.status === 'RESOLVED' ? 'Обработан' : '● Активен'}</span></td>
                            <td>${esc(s.fromName)}</td>
                            <td>${esc(s.message || '—')}</td>
                            <td class="ts">${esc(s.createdAt || '—')}</td>
                            <td>${s.status === 'RESOLVED' ? '' : `<button class="btn btn-primary btn-sm sos-resolve" type="button" data-id="${esc(s.workflowUid)}">Отметить решённым</button>`}</td>
                        </tr>`).join('')}</tbody>
                </table></div>`}
            </section>`;
            body.querySelectorAll('.sos-resolve').forEach(btn => {
                btn.addEventListener('click', async () => {
                    btn.disabled = true; btn.textContent = 'Обработка…';
                    try {
                        const { ok: o } = await apiJson(`/api/admin/sos/${btn.dataset.id}/resolve`, { method: 'POST', body: {} });
                        if (o) { toast('SOS обработан.', 'ok'); renderAdminSos(); refreshSosBadge(); }
                        else { toast('Не удалось обработать SOS.', 'err'); btn.disabled = false; btn.textContent = 'Отметить решённым'; }
                    } catch (_) { toast('Ошибка.', 'err'); btn.disabled = false; btn.textContent = 'Отметить решённым'; }
                });
            });
        });
    }

    async function refreshSosBadge() {
        const badge = document.getElementById('sos-badge');
        if (!badge) return;
        try {
            const { ok, data } = await apiJson('/api/admin/sos');
            if (!ok || !Array.isArray(data)) return;
            const open = data.filter(s => s.status !== 'RESOLVED').length;
            badge.textContent = open;
            badge.hidden = open === 0;
        } catch (_) { /* ignore */ }
    }

    // -------------------------------------------------------------
    //  Admin: employment verification queue (Problem 4). Citizens who
    //  registered as «Трудоустроен» + named an employer appear here as
    //  PENDING requests the company admin confirms or declines.
    // -------------------------------------------------------------
    function renderAdminEmployment() {
        const body = document.getElementById('admin-body');
        body.innerHTML = `<section class="panel panel-pad">${inlineLoad('Загрузка заявок…')}</section>`;
        apiJson('/api/admin/employment/pending').then(({ ok, data }) => {
            if (!ok || !Array.isArray(data)) { body.innerHTML = `<section class="panel panel-pad"><div class="obj-empty">Не удалось загрузить заявки.</div></section>`; return; }
            body.innerHTML = `
            <section class="panel panel-pad">
                <div class="section-title">Заявки на трудоустройство — ожидают: ${data.length}</div>
                <p class="muted" style="margin-bottom:14px">Гражданин при регистрации указал, что трудоустроен, и выбрал компанию.
                    Подтвердите, чтобы сделать его проверенным сотрудником организации (откроется рабочий режим),
                    или отклоните. До решения он пользуется системой как обычный гражданин.</p>
                ${data.length === 0 ? '<div class="obj-empty">Нет новых заявок на трудоустройство.</div>' : `
                <div class="table-scroll"><table class="audit-tbl">
                    <thead><tr><th>Заявитель</th><th>Логин</th><th>Компания</th><th>Роль</th><th>Подана</th><th></th></tr></thead>
                    <tbody>${data.map(r => `
                        <tr data-id="${esc(r.membershipUid)}">
                            <td>${esc(r.userName)}</td>
                            <td class="mono">${esc(r.username)}</td>
                            <td>${esc(r.organizationName)}</td>
                            <td>${esc(r.workRole || '—')}</td>
                            <td class="ts">${esc(r.createdAt || '—')}</td>
                            <td class="emp-actions">
                                <button class="btn btn-primary btn-sm emp-approve" type="button" data-id="${esc(r.membershipUid)}">Подтвердить</button>
                                <button class="btn btn-ghost btn-sm emp-reject" type="button" data-id="${esc(r.membershipUid)}">Отклонить</button>
                            </td>
                        </tr>`).join('')}</tbody>
                </table></div>`}
            </section>`;
            body.querySelectorAll('.emp-approve').forEach(btn =>
                btn.addEventListener('click', () => decideEmployment(btn.dataset.id, 'approve', btn)));
            body.querySelectorAll('.emp-reject').forEach(btn =>
                btn.addEventListener('click', () => decideEmployment(btn.dataset.id, 'reject', btn)));
        });
    }

    async function decideEmployment(membershipUid, action, btn) {
        const row = btn.closest('tr');
        if (row) row.querySelectorAll('button').forEach(b => { b.disabled = true; });
        try {
            const { ok, data } = await apiJson(`/api/admin/employment/${membershipUid}/${action}`, { method: 'POST', body: {} });
            if (ok) {
                toast(action === 'approve' ? 'Трудоустройство подтверждено.' : 'Заявка отклонена.', 'ok');
                renderAdminEmployment();
                refreshEmploymentBadge();
            } else {
                toast((data && data.message) || 'Не удалось обработать заявку.', 'err');
                if (row) row.querySelectorAll('button').forEach(b => { b.disabled = false; });
            }
        } catch (_) {
            toast('Ошибка обработки заявки.', 'err');
            if (row) row.querySelectorAll('button').forEach(b => { b.disabled = false; });
        }
    }

    async function refreshEmploymentBadge() {
        const badge = document.getElementById('emp-badge');
        if (!badge) return;
        try {
            const { ok, data } = await apiJson('/api/admin/employment/pending');
            if (!ok || !Array.isArray(data)) return;
            badge.textContent = data.length;
            badge.hidden = data.length === 0;
        } catch (_) { /* ignore */ }
    }

    let adminComplaintsPage = 0;
    function renderAdminComplaints(page = adminComplaintsPage) {
        adminComplaintsPage = page;
        const body = document.getElementById('admin-body');
        body.innerHTML = `<section class="panel panel-pad">${inlineLoad('Загрузка жалоб…')}</section>`;
        apiJson(`/api/admin/complaints?page=${page}&size=50`).then(({ ok, data }) => {
            const items = data && Array.isArray(data.content) ? data.content : null; // paginated (audit M-2)
            if (!ok || !items) { body.innerHTML = `<section class="panel panel-pad"><div class="obj-empty">Не удалось загрузить.</div></section>`; return; }
            body.innerHTML = `
            <section class="panel panel-pad">
                <div class="section-title">Жалобы (${data.totalElements})</div>
                ${items.length === 0 ? '<div class="obj-empty">Жалоб нет.</div>' : `
                <div class="table-scroll"><table class="audit-tbl">
                    <thead><tr><th>Тема</th><th>Категория</th><th>Статус</th><th></th></tr></thead>
                    <tbody>${items.map((c, i) => `
                        <tr>
                            <td>${esc(c.subject)}</td>
                            <td>${esc(c.category)}</td>
                            <td><span class="cmp-status s-${esc((c.status || '').toLowerCase())}">${esc(COMPLAINT_RU[c.status] || c.status)}</span></td>
                            <td><button class="btn btn-ghost btn-sm cmp-open" type="button" data-i="${i}">Открыть и ответить</button></td>
                        </tr>`).join('')}</tbody>
                </table></div>${pagerHtml(data)}`}
            </section>`;
            body.querySelectorAll('.cmp-open').forEach(btn => {
                btn.addEventListener('click', () => openComplaintModal(items[Number(btn.dataset.i)]));
            });
            bindPager(body, data, p => renderAdminComplaints(p));
        });
    }

    // Admin opens a complaint, reads it in full, sets a status and writes a reply that is
    // delivered to the author as a notification (Point 4 — "админ не может открыть/ответить").
    function openComplaintModal(c) {
        const statuses = ['NEW', 'IN_PROGRESS', 'RESOLVED', 'REJECTED'];
        showModal({
            title: 'Жалоба · ' + (c.subject || ''),
            bodyHtml: `
                <div class="cmp-detail">
                    ${kv('Тема', c.subject || '—')}
                    ${kv('Категория', c.category || '—')}
                    ${kv('Дата', c.createdAt || '—')}
                    ${kv('Текущий статус', COMPLAINT_RU[c.status] || c.status)}
                </div>
                <div class="field" style="margin-top:12px"><label>Описание</label>
                    <div class="cmp-desc-box">${esc(c.description || '—')}</div></div>
                <div class="field" style="margin-top:10px"><label for="cmp-new-status">Новый статус</label>
                    <select id="cmp-new-status">${statuses.map(s => `<option value="${s}" ${s === c.status ? 'selected' : ''}>${esc(COMPLAINT_RU[s])}</option>`).join('')}</select></div>
                <div class="field" style="margin-top:10px"><label for="cmp-response">Ответ пользователю</label>
                    <textarea id="cmp-response" maxlength="140" placeholder="Кратко опишите решение — пользователь получит его одним уведомлением"></textarea>
                    <div class="field-hint" id="cmp-resp-count"></div></div>`,
            dismissValue: null,
            onBody: (card, { close }) => {
                // The reply travels inside a notification title capped at 240 chars together
                // with the complaint subject (audit FB-4) — cap the input from the subject's
                // length so the send can never overflow and fail server-side.
                const respEl = card.querySelector('#cmp-response');
                const countEl = card.querySelector('#cmp-resp-count');
                const cap = Math.max(30, Math.min(140, 190 - String(c.subject || '').length));
                respEl.maxLength = cap;
                const syncCap = () => { countEl.textContent = respEl.value.length + ' / ' + cap; };
                respEl.addEventListener('input', syncCap);
                syncCap();
                const box = card.querySelector('.modal-actions');
                box.innerHTML = `
                    <button class="btn btn-ghost" type="button" id="cmp-cancel">Закрыть</button>
                    <button class="btn btn-primary" type="button" id="cmp-send" data-autofocus>Отправить ответ</button>`;
                card.querySelector('#cmp-cancel').addEventListener('click', () => close(null));
                card.querySelector('#cmp-send').addEventListener('click', async () => {
                    const status = card.querySelector('#cmp-new-status').value;
                    const message = card.querySelector('#cmp-response').value.trim();
                    const sendBtn = card.querySelector('#cmp-send');
                    sendBtn.disabled = true; sendBtn.textContent = 'Отправка…';
                    try {
                        const { ok, data } = await apiJson(`/api/admin/complaints/${c.complaintUid}/respond`,
                            { method: 'POST', body: { status, message } });
                        if (!ok) throw new Error((data && data.message) || 'Не удалось отправить ответ.');
                        toast('Ответ отправлен пользователю.', 'ok');
                        close(true);
                        renderAdminComplaints();
                    } catch (e) { toast(e.message, 'err'); sendBtn.disabled = false; sendBtn.textContent = 'Отправить ответ'; }
                });
            }
        });
    }

    // (Modules management removed — P1: the tab was unreachable and toggling a module gated
    //  nothing in the pipeline. The dead UI + its API are gone.)

    // =============================================================
    //  CITIZEN VIEW
    // =============================================================
    // Affiliation pill for the identity strip (Problem 4): a confirmed employee shows their
    // organization with a check; a still-unconfirmed claim shows "на проверке" so the applicant
    // can see their employment request is pending the company admin's decision.
    function employmentPillHtml() {
        if (!currentUser || currentUser.guest) return '';
        const state = currentUser.employmentState;
        const org = currentUser.organizationName;
        if (!org) return '';
        if (state === 'ACTIVE') {
            return `<div class="id-pill"><span class="idp-k">Организация</span>
                <span class="idp-v emp-ok" title="Трудоустройство подтверждено администратором">${esc(org)} ✓</span></div>`;
        }
        if (state === 'PENDING') {
            return `<div class="id-pill"><span class="idp-k">Трудоустройство</span>
                <span class="idp-v emp-pending" title="Заявка ожидает подтверждения администратора компании">${esc(org)} · на проверке</span></div>`;
        }
        return '';
    }

    function renderCitizen() {
        const guest = !!currentUser.guest;
        // Guests get a deliberately reduced surface. They have NO personal QR to demonstrate —
        // an unregistered visitor is here to scan, not to be scanned — so the "Мой QR" tab is
        // hidden for them (it returns after they register). History / complaints are likewise
        // tracked silently and only surface post-registration (Point 6).
        const tabs = guest
            ? [['terminal', 'Терминал']]
            : [['home', 'Главная'], ['terminal', 'Терминал'], ['myqr', 'Мой QR'], ['objects', 'Мои объекты'],
               ['history', 'Моя история'], ['complaints', 'Жалобы']];
        // Модульные вью (Медицина / Услуги / Бизнес) открываются с «Главной» и не дублируются в навигации.
        const moduleTabs = ['medicine', 'services', 'business'];
        if (!tabs.some(t => t[0] === citizenTab) && !(!guest && moduleTabs.includes(citizenTab))) {
            citizenTab = guest ? 'terminal' : 'home';
        }
        app().innerHTML = `
        <div class="fade-in">
            <div class="page-head">
                <div>
                    <h2>Единый национальный QR</h2>
                    <p>Один личный QR — разные данные для разных ролей: врач видит медкарту,
                    фармацевт — рецепты, полицейский — правовой статус, гражданин — визитку.</p>
                </div>
            </div>

            <div class="identity-strip">
                <div class="id-pill"><span class="idp-k">Личность</span>
                    <span class="idp-v primary">${esc(shortId(currentUser.identityUid))}</span></div>
                ${currentUser.guest ? '' : `<div class="id-pill"><span class="idp-k">Основной QR</span>
                    <span class="idp-v gold">${esc(shortId(currentUser.primaryQrUid))}</span></div>`}
                ${employmentPillHtml()}
                <div class="id-pill"><span class="idp-k">Уровень доверия</span>
                    <span class="idp-v gold" title="Единая метрика доверия, на которую опирается движок решений (мед. ≥ 70, инфраструктура ≥ 60)">${esc(currentUser.trustLevel)} / 100</span></div>
                <div class="id-pill"><span class="idp-k">Роли</span>
                    <span class="idp-v">${esc((currentUser.roles || []).join(', '))}</span></div>
                <div class="id-pill"><span class="idp-k">Риск</span>
                    <span class="idp-v">${esc(currentUser.riskScore || 'NORMAL')}</span></div>
            </div>

            ${currentUser.guest ? `<div class="guest-banner">
                <span>Вы вошли как <strong>гость</strong>. Действия записываются. Зарегистрируйтесь, чтобы сохранить историю.</span>
                <button class="btn btn-ghost btn-sm" id="guest-register-btn" type="button">Зарегистрироваться</button>
            </div>` : ''}

            <div class="context-bar">
                <div class="cb-mode">
                    <span class="cb-label">Режим:</span>
                    <span id="cb-mode-badge" class="cb-badge personal">Личный режим</span>
                </div>
                <div class="cb-actions">
                    <button id="cb-mode-toggle" class="btn btn-ghost btn-sm" type="button">Перейти в рабочий режим</button>
                    <button id="cb-sos" class="btn btn-danger btn-sm" type="button">🆘 SOS</button>
                    <button id="cb-notif" class="btn btn-ghost btn-sm" type="button">🔔 Уведомления<span id="cb-notif-count" class="cb-count" hidden>0</span></button>
                </div>
            </div>
            <div id="cb-notif-panel" class="cb-notif-panel" hidden></div>

            <div id="access-requests" class="access-requests"></div>

            <div class="view-nav">
                ${tabs.map(([k, label]) => `<button data-tab="${k}" type="button">${esc(label)}</button>`).join('')}
            </div>
            <div id="citizen-body"></div>
        </div>`;

        const nav = app().querySelector('.view-nav');
        nav.querySelectorAll('button').forEach(b => {
            b.classList.toggle('active', b.dataset.tab === citizenTab
                || (b.dataset.tab === 'home' && moduleTabs.includes(citizenTab)));
            b.addEventListener('click', () => { citizenTab = b.dataset.tab; renderCitizen(); });
        });

        wireContextBar();
        loadCitizenContext();
        loadAccessRequests();
        startAccessPolling();

        if (citizenTab === 'home') renderCitizenHome();
        else if (citizenTab === 'terminal') renderCitizenTerminal();
        else if (citizenTab === 'myqr') renderCitizenMyQr();
        else if (citizenTab === 'objects') renderCitizenObjects();
        else if (citizenTab === 'history') renderCitizenHistory();
        else if (citizenTab === 'complaints') renderCitizenComplaints();
        else if (citizenTab === 'medicine') renderCitizenMedicine();
        else if (citizenTab === 'services') renderCitizenServices();
        else if (citizenTab === 'business') renderCitizenBusiness();
        else renderCitizenTerminal();
    }

    // =============================================================
    //  PHASE 2 — ДАШБОРД: «Главная» с тремя модулями
    //  (Медицина и здоровье · Услуги и быт · Бизнес и магазины)
    // =============================================================
    async function loadDossier(force) {
        if (dossierInfo && !force) return dossierInfo;
        try {
            const { ok, data } = await apiJson('/api/v2/dossier/me');
            if (ok && data && data.available) dossierInfo = data;
        } catch (_) { /* ignore */ }
        return dossierInfo;
    }

    function moduleBack() {
        return `<button class="btn btn-ghost btn-sm mod-back" id="mod-back" type="button">← Главная</button>`;
    }
    function wireModuleBack() {
        const b = document.getElementById('mod-back');
        if (b) b.addEventListener('click', () => { citizenTab = 'home'; renderCitizen(); });
    }

    function renderCitizenHome() {
        const body = document.getElementById('citizen-body');
        body.innerHTML = `<section class="panel panel-pad">${inlineLoad('Собираем ваш цифровой профиль…')}</section>`;
        loadDossier().then(d => {
            const name = `${currentUser.firstName || ''} ${currentUser.lastName || ''}`.trim();
            const initials = ((currentUser.firstName || '')[0] || '') + ((currentUser.lastName || '')[0] || '');
            const egov = (d && d.egov) || {};
            const rxActive = d ? Number(d.prescriptionsActive || 0) : 0;
            body.innerHTML = `
            <div class="dash fade-in">
                <section class="dash-hero panel">
                    <div class="dh-glow" aria-hidden="true"></div>
                    <div class="dh-id">
                        <div class="dh-avatar">${esc(initials.toUpperCase())}</div>
                        <div class="dh-meta">
                            <div class="dh-name">${esc(name)}</div>
                            <div class="dh-sub">${esc(currentUser.professionLabel || 'Гражданин')} · подтверждено eGov <span class="demo-tag">DEMO</span></div>
                            <div class="dh-kv">
                                ${egov.iinMasked ? `<span class="dh-pill">ИИН <code>${esc(egov.iinMasked)}</code></span>` : ''}
                                ${egov.address ? `<span class="dh-pill">📍 ${esc(egov.address)}</span>` : ''}
                            </div>
                        </div>
                        <button class="btn btn-gold dh-qr-btn" id="dh-myqr" type="button">▦ Мой единый QR</button>
                    </div>
                    ${d ? `<div class="dh-docs">
                        <span class="dh-docs-label">Мои документы:</span>
                        <button class="dh-doc" data-scan="${esc(d.medicalObjectUid)}" type="button">🩺 Медкарта</button>
                        <button class="dh-doc" data-scan="${esc(d.legalObjectUid)}" type="button">📜 Справка о несудимости</button>
                        <button class="dh-doc" data-scan="${esc(d.vcardObjectUid)}" type="button">👤 Визитка</button>
                    </div>` : ''}
                </section>

                <div class="dash-modules">
                    <button class="module-card mod-med" data-mod="medicine" type="button">
                        <div class="mc-ico">🫀</div>
                        <div class="mc-name">Медицина и здоровье</div>
                        <div class="mc-sub">Медкарта · приёмы · рецепты</div>
                        <div class="mc-stat">${rxActive > 0
                            ? `<span class="mc-badge">${rxActive} активн. рецепт${rxActive === 1 ? '' : 'а'}</span>`
                            : '<span class="mc-badge quiet">Показатели в норме</span>'}</div>
                        <span class="mc-arrow" aria-hidden="true">→</span>
                    </button>
                    <button class="module-card mod-serv" data-mod="services" type="button">
                        <div class="mc-ico">🏠</div>
                        <div class="mc-name">Услуги и быт</div>
                        <div class="mc-sub">ЖКХ · вывоз мусора · мастера</div>
                        <div class="mc-stat"><span class="mc-badge quiet">Заявка в 1 клик — адрес из eGov</span></div>
                        <span class="mc-arrow" aria-hidden="true">→</span>
                    </button>
                    <button class="module-card mod-biz" data-mod="business" type="button">
                        <div class="mc-ico">🛍</div>
                        <div class="mc-name">Бизнес и магазины</div>
                        <div class="mc-sub">Покупки · скидочная карта · мои вещи</div>
                        <div class="mc-stat"><span class="mc-badge quiet">Единый QR = карта лояльности</span></div>
                        <span class="mc-arrow" aria-hidden="true">→</span>
                    </button>
                </div>

                <section class="panel panel-pad dash-how">
                    <div class="section-title">Как работает контекстный QR</div>
                    <p class="muted" style="margin-bottom:12px">QR один и тот же — данные разные: платформа
                    смотрит на роль и рабочий режим того, кто сканирует.</p>
                    <div class="how-grid">
                        <div class="how-item"><span class="hi-ico">👤</span><b>Гражданин</b><span>видит вашу визитку</span></div>
                        <div class="how-item"><span class="hi-ico">🩺</span><b>Врач</b><span>медкарту — только с вашего согласия</span></div>
                        <div class="how-item"><span class="hi-ico">💊</span><b>Фармацевт</b><span>только рецепты к выдаче</span></div>
                        <div class="how-item"><span class="hi-ico">👮</span><b>Полицейский</b><span>правовой статус и штрафы</span></div>
                    </div>
                </section>
            </div>`;
            const qrBtn = document.getElementById('dh-myqr');
            if (qrBtn) qrBtn.addEventListener('click', () => { citizenTab = 'myqr'; renderCitizen(); });
            body.querySelectorAll('.module-card').forEach(c =>
                c.addEventListener('click', () => { citizenTab = c.dataset.mod; renderCitizen(); }));
            body.querySelectorAll('.dh-doc').forEach(b =>
                b.addEventListener('click', () => doScan(b.dataset.scan)));
        });
    }

    // ---- Модуль 1: МЕДИЦИНА И ЗДОРОВЬЕ ----
    function renderCitizenMedicine() {
        const body = document.getElementById('citizen-body');
        body.innerHTML = `<section class="panel panel-pad">${inlineLoad('Загрузка модуля…')}</section>`;
        loadDossier().then(async d => {
            let rxHtml = '<div class="obj-empty">Назначений пока нет.</div>';
            if (d) {
                try {
                    const { ok, data } = await apiJson(`/api/v2/medical/${encodeURIComponent(d.medicalObjectUid)}/prescriptions`);
                    if (ok && Array.isArray(data) && data.length) rxHtml = prescriptionsSection(data, d.medicalObjectUid);
                } catch (_) { /* keep empty */ }
            }
            body.innerHTML = `
            <div class="module-view fade-in">
                <div class="module-head mod-med-head">
                    ${moduleBack()}
                    <div class="mh-title"><span class="mh-ico">🫀</span> Медицина и здоровье</div>
                </div>
                <div class="mod-actions">
                    <button class="mod-action" id="med-open-card" type="button" ${d ? '' : 'disabled'}>
                        <span class="ma-ico">🩺</span>
                        <span class="ma-txt"><b>Моя медицинская карта</b><span>аллергии, назначения, динамика давления</span></span>
                        <span class="ma-arrow">→</span>
                    </button>
                    <button class="mod-action" id="med-goto-qr" type="button">
                        <span class="ma-ico">▦</span>
                        <span class="ma-txt"><b>Показать QR врачу</b><span>врач откроет карту только с вашего согласия</span></span>
                        <span class="ma-arrow">→</span>
                    </button>
                </div>
                <section class="panel panel-pad">
                    <div class="section-title">Мои рецепты</div>
                    ${rxHtml}
                </section>
                <section class="panel panel-pad scenario-strip">
                    <div class="section-title">Сценарий «Пациент → Врач → Фармацевт»</div>
                    <div class="steps">
                        <div class="step"><span class="st-n">1</span><b>Пациент</b><span>показывает единый QR</span></div>
                        <div class="step"><span class="st-n">2</span><b>Врач</b><span>сканирует, получает согласие, выписывает рецепт</span></div>
                        <div class="step"><span class="st-n">3</span><b>Фармацевт</b><span>сканирует тот же QR и видит только рецепты</span></div>
                    </div>
                </section>
            </div>`;
            wireModuleBack();
            const open = document.getElementById('med-open-card');
            if (open && d) open.addEventListener('click', () => doScan(d.medicalObjectUid));
            const qr = document.getElementById('med-goto-qr');
            if (qr) qr.addEventListener('click', () => { citizenTab = 'myqr'; renderCitizen(); });
            wirePrescriptions(d ? d.medicalObjectUid : null, body);
        });
    }

    // ---- Модуль 2: УСЛУГИ И БЫТ ----
    function isServiceOperator() {
        return !!(currentUser && (currentUser.roles || []).includes('SERVICE_OPERATOR'));
    }

    function renderCitizenServices() {
        const body = document.getElementById('citizen-body');
        body.innerHTML = `<section class="panel panel-pad">${inlineLoad('Загрузка каталога услуг…')}</section>`;
        const operator = isServiceOperator();
        Promise.all([
            apiJson('/api/v2/services/catalog'),
            apiJson('/api/v2/services/mine'),
            loadDossier(),
            operator ? apiJson('/api/v2/services/queue') : Promise.resolve({ ok: false })
        ]).then(([cat, mine, d, q]) => {
                const catalog = (cat.ok && Array.isArray(cat.data)) ? cat.data : [];
                const orders = (mine.ok && Array.isArray(mine.data)) ? mine.data : [];
                const queue = (q.ok && Array.isArray(q.data)) ? q.data : [];
                const address = d && d.egov && d.egov.address ? d.egov.address : null;
                servicesOrdersSig = ordersSig(orders);
                servicesQueueSig = ordersSig(queue);
                body.innerHTML = `
                <div class="module-view fade-in">
                    <div class="module-head mod-serv-head">
                        ${moduleBack()}
                        <div class="mh-title"><span class="mh-ico">🏠</span> Услуги и быт</div>
                    </div>
                    ${operator ? `
                    <section class="panel panel-pad">
                        <div class="section-title">🛠 Очередь исполнителя</div>
                        <p class="muted" style="margin-bottom:10px">Заявки граждан со всей платформы. Примите заявку, выполните работу и отметьте завершение — заказчик подтвердит выполнение со своей стороны.</p>
                        <div id="svc-queue">${queueHtml(queue)}</div>
                    </section>` : ''}
                    ${address ? `<div class="serv-addr">📍 Заявки привязываются к вашему адресу из eGov: <b>${esc(address)}</b></div>` : ''}
                    <div class="serv-grid">
                        ${catalog.map(c => `
                        <div class="serv-card">
                            <div class="sc-ico">${esc(c.icon)}</div>
                            <div class="sc-name">${esc(c.label)}</div>
                            <div class="sc-desc">${esc(c.description)}</div>
                            <div class="sc-meta">${esc(c.operator)} · ${esc(c.price)} · ${esc(c.eta)}</div>
                            <button class="btn btn-primary btn-sm sc-order" type="button" data-key="${esc(c.key)}">Заказать</button>
                        </div>`).join('')}
                    </div>
                    <section class="panel panel-pad">
                        <div class="section-title">Мои заявки</div>
                        <div id="serv-orders">${serviceOrdersHtml(orders)}</div>
                    </section>
                </div>`;
                wireModuleBack();
                body.querySelectorAll('.sc-order').forEach(btn => btn.addEventListener('click', async () => {
                    btn.disabled = true; btn.textContent = 'Оформляем…';
                    try {
                        const { ok, data } = await apiJson('/api/v2/services/order',
                            { method: 'POST', body: { service: btn.dataset.key } });
                        if (!ok) throw new Error((data && data.message) || 'Не удалось оформить заявку.');
                        toast(`Заявка принята: ${data.label}. Ожидает исполнителя.`, 'ok');
                        refreshNotifications();
                        refreshServicesLists();
                    } catch (e) {
                        toast(e.message, 'err');
                        btn.disabled = false; btn.textContent = 'Заказать';
                    }
                }));
                wireServiceOrderActions(body);
                wireQueueActions(body);
                startServicesPolling();
            });
    }

    const SERVICE_STATUS_RU = {
        NEW: 'Ожидает исполнителя', ACCEPTED: 'В работе', DONE: 'Выполнена исполнителем',
        COMPLETED: 'Завершена', DECLINED: 'Отклонена'
    };

    function serviceStatusTag(o) {
        const cls = { NEW: 'review', ACCEPTED: 'info', DONE: 'review', COMPLETED: 'ok', DECLINED: 'bad' }[o.status] || 'info';
        const label = SERVICE_STATUS_RU[o.status] || o.status;
        return `<span class="atag ${cls}">${esc(label)}</span>`;
    }

    function ordersSig(rows) {
        return rows.map(o => `${o.orderUid}:${o.status}:${o.assigneeName || ''}`).join('|');
    }

    // Заказчик: живой статус заявки + «Подтвердить выполнение», когда исполнитель завершил работу.
    function serviceOrdersHtml(orders) {
        if (!orders.length) return '<div class="obj-empty">Заявок пока нет. Закажите услугу выше — она привяжется к вашему профилю.</div>';
        return orders.map(o => `
            <div class="order-row">
                <span class="or-ico">${esc(o.icon || '🧾')}</span>
                <div class="or-main">
                    <div class="or-name">${esc(o.label || o.service)}</div>
                    <div class="or-meta">${esc([o.address, o.assigneeName ? 'Исполнитель: ' + o.assigneeName : o.operator, o.orderedAt].filter(Boolean).join(' · '))}</div>
                </div>
                <div class="or-right">
                    ${serviceStatusTag(o)}
                    ${o.status === 'DONE'
                        ? `<button class="btn btn-primary btn-sm or-complete" type="button" data-id="${esc(o.orderUid)}">Подтвердить выполнение</button>`
                        : ''}
                </div>
            </div>`).join('');
    }

    // Исполнитель: очередь заявок с кнопками «Принять в работу» / «Завершить».
    function queueHtml(queue) {
        if (!queue.length) return '<div class="obj-empty">Новых заявок нет. Как только гражданин закажет услугу — она появится здесь.</div>';
        return queue.map(o => `
            <div class="order-row">
                <span class="or-ico">${esc(o.icon || '🧾')}</span>
                <div class="or-main">
                    <div class="or-name">${esc(o.label || o.service)}</div>
                    <div class="or-meta">${esc([o.customerName, o.address, o.orderedAt].filter(Boolean).join(' · '))}</div>
                    ${o.note ? `<div class="or-meta">💬 ${esc(o.note)}</div>` : ''}
                </div>
                <div class="or-right">
                    ${o.status === 'NEW'
                        ? `<button class="btn btn-primary btn-sm svc-accept" type="button" data-id="${esc(o.orderUid)}">Принять в работу</button>`
                        : o.status === 'ACCEPTED' && o.assigneeMe
                            ? `<span class="atag info">В работе у вас</span>
                               <button class="btn btn-gold btn-sm svc-finish" type="button" data-id="${esc(o.orderUid)}">Завершить</button>`
                            : o.status === 'DONE'
                                ? '<span class="atag review">Ждёт подтверждения заказчика</span>'
                                : serviceStatusTag(o)}
                </div>
            </div>`).join('');
    }

    function wireServiceOrderActions(scope) {
        scope.querySelectorAll('.or-complete').forEach(btn => btn.addEventListener('click', async () => {
            btn.disabled = true;
            try {
                const { ok, data } = await apiJson(`/api/v2/services/${btn.dataset.id}/complete`, { method: 'POST', body: {} });
                if (!ok) throw new Error((data && data.message) || 'Не удалось обновить заявку.');
                toast('Выполнение подтверждено. Спасибо!', 'ok');
                refreshNotifications();
                refreshServicesLists();
            } catch (e) { toast(e.message, 'err'); btn.disabled = false; }
        }));
    }

    function wireQueueActions(scope) {
        scope.querySelectorAll('.svc-accept').forEach(btn => btn.addEventListener('click', async () => {
            btn.disabled = true; btn.textContent = 'Принимаем…';
            try {
                const { ok, data } = await apiJson(`/api/v2/services/${btn.dataset.id}/accept`, { method: 'POST', body: {} });
                if (!ok) throw new Error((data && data.message) || 'Не удалось принять заявку.');
                toast('Заявка принята в работу.', 'ok');
                refreshServicesLists();
            } catch (e) { toast(e.message, 'err'); btn.disabled = false; btn.textContent = 'Принять в работу'; }
        }));
        scope.querySelectorAll('.svc-finish').forEach(btn => btn.addEventListener('click', async () => {
            btn.disabled = true; btn.textContent = 'Завершаем…';
            try {
                const { ok, data } = await apiJson(`/api/v2/services/${btn.dataset.id}/finish`, { method: 'POST', body: {} });
                if (!ok) throw new Error((data && data.message) || 'Не удалось завершить заявку.');
                toast('Работа завершена. Заказчик получил запрос на подтверждение.', 'ok');
                refreshServicesLists();
            } catch (e) { toast(e.message, 'err'); btn.disabled = false; btn.textContent = 'Завершить'; }
        }));
    }

    // Тихое обновление обоих списков (после действия — принудительно, из поллинга — по отпечатку,
    // чтобы фоновая перерисовка не «съедала» клик, как в поллинге согласий).
    async function refreshServicesLists(fromPoll) {
        const ordersBox = document.getElementById('serv-orders');
        if (!ordersBox) return;
        try {
            const mine = await apiJson('/api/v2/services/mine');
            if (mine.ok && Array.isArray(mine.data)) {
                const sig = ordersSig(mine.data);
                if (!fromPoll || sig !== servicesOrdersSig) {
                    servicesOrdersSig = sig;
                    ordersBox.innerHTML = serviceOrdersHtml(mine.data);
                    wireServiceOrderActions(ordersBox);
                }
            }
            const qBox = document.getElementById('svc-queue');
            if (qBox && isServiceOperator()) {
                const q = await apiJson('/api/v2/services/queue');
                if (q.ok && Array.isArray(q.data)) {
                    const sig = ordersSig(q.data);
                    if (!fromPoll || sig !== servicesQueueSig) {
                        servicesQueueSig = sig;
                        qBox.innerHTML = queueHtml(q.data);
                        wireQueueActions(qBox);
                    }
                }
            }
        } catch (_) { /* transient — keep current lists */ }
    }

    // Пока открыт экран «Услуги и быт», статусы обновляются сами: заказчик видит «принята/выполнена»
    // без F5, исполнитель видит новые заявки. Таймер сам сворачивается, когда экран покинут.
    function startServicesPolling() {
        if (servicesPollTimer) return;
        servicesPollTimer = setInterval(() => {
            if (!document.getElementById('serv-orders')) {
                clearInterval(servicesPollTimer); servicesPollTimer = null;
                return;
            }
            if (document.hidden) return;
            refreshServicesLists(true);
        }, 5000);
    }

    // ---- Модуль 3: БИЗНЕС И МАГАЗИНЫ ----
    function renderCitizenBusiness() {
        const body = document.getElementById('citizen-body');
        body.innerHTML = `<section class="panel panel-pad">${inlineLoad('Загрузка модуля…')}</section>`;
        Promise.all([apiJson('/api/v2/my-qr'), apiJson('/api/v2/my-objects')]).then(([qr, obj]) => {
            const qrData = qr.ok ? qr.data : null;
            const objects = (obj.ok && Array.isArray(obj.data)) ? obj.data : [];
            body.innerHTML = `
            <div class="module-view fade-in">
                <div class="module-head mod-biz-head">
                    ${moduleBack()}
                    <div class="mh-title"><span class="mh-ico">🛍</span> Бизнес и магазины</div>
                </div>
                <div class="biz-grid">
                    <section class="loyalty-card">
                        <div class="lc-glow" aria-hidden="true"></div>
                        <div class="lc-brand">IDEAQR <span>PAY&nbsp;ID</span></div>
                        <div class="lc-name">${esc(qrData ? qrData.fullName : '')}</div>
                        ${qrData ? `<img class="lc-qr" src="${esc(qrData.qrImageDataUri)}" alt="QR карты лояльности">` : ''}
                        <div class="lc-num mono">${esc(shortId(currentUser.identityUid))} · скидка 10%</div>
                        <div class="lc-note">Единый QR — это и карта лояльности: продавец увидит только скидку, не личные данные.</div>
                    </section>
                    <section class="panel panel-pad biz-side">
                        <div class="section-title">Демо-сценарии покупки</div>
                        <div class="quick-chips">
                            <button class="quick-chip" type="button" data-scan="RETAIL_NIKE_AF1">
                                <span class="qc-name">Nike Air Force 1</span><span class="qc-code">RETAIL_NIKE_AF1</span>
                                <span class="qc-tag">товар · цены и наличие</span></button>
                            <button class="quick-chip" type="button" data-scan="CAR_TOYOTA_CAMRY">
                                <span class="qc-name">Toyota Camry 2024</span><span class="qc-code">CAR_TOYOTA_CAMRY</span>
                                <span class="qc-tag">авто · владение и передача</span></button>
                        </div>
                    </section>
                </div>
                <section class="panel panel-pad">
                    <div class="section-title">Мои вещи (${objects.length})</div>
                    ${objects.length ? `<div class="myobj-grid">
                        ${objects.map(o => `
                        <div class="myobj-card">
                            <img class="myobj-qr" src="${esc(o.qrImageDataUri)}" alt="QR ${esc(o.displayName)}">
                            <div class="myobj-name">${esc(o.displayName)}</div>
                            <div class="myobj-uid mono">${esc(o.objectUid)}</div>
                            <button class="btn btn-ghost btn-sm myobj-open" type="button" data-uid="${esc(o.objectUid)}">Открыть карточку</button>
                        </div>`).join('')}
                    </div>` : '<div class="obj-empty">Пока пусто. Когда вам передадут объект (например, автомобиль) — он появится здесь с QR-кодом.</div>'}
                </section>
            </div>`;
            wireModuleBack();
            body.querySelectorAll('[data-scan]').forEach(b => b.addEventListener('click', () => doScan(b.dataset.scan)));
            body.querySelectorAll('.myobj-open').forEach(b => b.addEventListener('click', () => doScan(b.dataset.uid)));
        });
    }

    // -------------------------------------------------------------
    //  Citizen: "Мои объекты" — objects I own, incl. ones transferred to me
    // -------------------------------------------------------------
    function renderCitizenObjects() {
        const body = document.getElementById('citizen-body');
        body.innerHTML = `<section class="panel panel-pad">${inlineLoad('Загрузка объектов…')}</section>`;
        apiJson('/api/v2/my-objects').then(({ ok, data }) => {
            if (!ok || !Array.isArray(data)) {
                body.innerHTML = `<section class="panel panel-pad"><div class="obj-empty">Не удалось загрузить объекты.</div></section>`;
                return;
            }
            if (data.length === 0) {
                body.innerHTML = `<section class="panel panel-pad">
                    <div class="section-title">Мои объекты</div>
                    <div class="obj-empty">У вас пока нет объектов. Когда администратор передаст вам объект (например, автомобиль), он появится здесь — с QR-кодом, по которому его можно показать или проверить.</div>
                </section>`;
                return;
            }
            body.innerHTML = `
            <section class="panel panel-pad">
                <div class="section-title">Мои объекты (${data.length})</div>
                <p class="muted" style="margin-bottom:14px">Объекты, которыми вы владеете. Переданный вам объект отображается здесь — вы можете показать его QR-код или отсканировать сами.</p>
                <div class="myobj-grid">
                    ${data.map(o => `
                    <div class="myobj-card">
                        <img class="myobj-qr" src="${esc(o.qrImageDataUri)}" alt="QR ${esc(o.displayName)}">
                        <div class="myobj-name">${esc(o.displayName)}</div>
                        <div class="myobj-uid mono">${esc(o.objectUid)}</div>
                        <div class="myobj-meta">${esc(categoryLabel(o.category))} · вы владелец</div>
                        <button class="btn btn-ghost btn-sm myobj-open" type="button" data-uid="${esc(o.objectUid)}">Открыть карточку</button>
                    </div>`).join('')}
                </div>
            </section>`;
            body.querySelectorAll('.myobj-open').forEach(btn => {
                btn.addEventListener('click', () => doScan(btn.dataset.uid));
            });
        });
    }

    // ---- Citizen context: working mode, SOS, notifications ----
    async function loadCitizenContext() {
        try {
            const { ok, data } = await apiJson('/api/v2/session');
            if (ok && data) { sessionInfo = data; renderModeBar(); }
        } catch (_) { /* ignore */ }
        refreshNotifications();
    }

    function renderModeBar() {
        const badge = document.getElementById('cb-mode-badge');
        const toggle = document.getElementById('cb-mode-toggle');
        if (!badge || !toggle || !sessionInfo) return;
        const working = sessionInfo.mode === 'WORKING';
        badge.textContent = working
            ? 'Рабочий · ' + (sessionInfo.activeOrganizationName || '')
            : 'Личный режим';
        badge.className = 'cb-badge ' + (working ? 'working' : 'personal');
        const orgs = sessionInfo.organizations || [];
        if (working) { toggle.textContent = 'Завершить рабочий режим'; toggle.disabled = false; }
        else if (orgs.length === 0) { toggle.textContent = 'Рабочий режим недоступен'; toggle.disabled = true; }
        else { toggle.textContent = 'Перейти в рабочий режим'; toggle.disabled = false; }
    }

    async function toggleWorkingMode() {
        if (!sessionInfo) return;
        try {
            if (sessionInfo.mode === 'WORKING') {
                const { ok, data } = await apiJson('/api/v2/mode/personal', { method: 'POST', body: {} });
                if (ok) { sessionInfo = data; toast('Рабочий режим завершён.', 'info'); }
            } else {
                const orgs = sessionInfo.organizations || [];
                let orgUid = orgs.length ? orgs[0].organizationUid : null;
                if (orgs.length > 1) {
                    const options = orgs.map(o => ({ label: `${o.name} (${o.role})`, value: o.organizationUid }));
                    const picked = await modalSelect('Выберите организацию для рабочего режима:', options,
                        { title: 'Рабочий режим' });
                    if (!picked) return;
                    orgUid = picked;
                }
                const { ok, data } = await apiJson('/api/v2/mode/work',
                    { method: 'POST', body: orgUid ? { organizationUid: orgUid } : {} });
                if (ok) { sessionInfo = data; toast('Рабочий режим активирован.', 'ok'); }
                else { toast((data && data.message) || 'Не удалось включить рабочий режим.', 'err'); }
            }
            renderModeBar();
            refreshNotifications();
        } catch (_) { toast('Ошибка переключения режима.', 'err'); }
    }

    async function sendSOS() {
        const ok = await modalConfirm('Отправить SOS-сигнал? Будет создан приоритетный запрос.',
            { title: 'SOS', confirmText: 'Отправить SOS', danger: true });
        if (!ok) return;
        try {
            const { ok, data } = await apiJson('/api/v2/sos',
                { method: 'POST', body: { message: 'SOS из терминала' } });
            if (ok && data) {
                toast('SOS-сигнал зарегистрирован.', 'ok');
                // Honest copy (audit FB-6): an SOS is not an access verdict, and the alert is a
                // queue the administrator opens in «Тревоги» — not a delivered notification.
                const slot = openResultPage(
                    `${verdictHtml('APPROVED',
                        'SOS-сигнал зафиксирован в неизменяемом журнале и передан в панель «Тревоги» администратора.',
                        data.riskLevel, 'Сигнал отправлен')}<div class="pipeline" id="sos-pipeline"></div>`,
                    'SOS');
                animatePipeline(slot.querySelector('#sos-pipeline'), data, 'APPROVED');
                refreshNotifications();
            } else {
                toast((data && data.message) || 'Не удалось отправить SOS.', 'err');
            }
        } catch (_) { toast('Ошибка отправки SOS.', 'err'); }
    }

    async function refreshNotifications() {
        try {
            const { ok, data } = await apiJson('/api/v2/notifications');
            if (!ok || !Array.isArray(data)) return;
            notifList = data;
            const unread = data.filter(n => n.status === 'NEW').length;
            const c = document.getElementById('cb-notif-count');
            if (c) { c.textContent = unread; c.hidden = unread === 0; }
            renderNotifPanel();
        } catch (_) { /* ignore */ }
    }

    function renderNotifPanel() {
        const panel = document.getElementById('cb-notif-panel');
        if (!panel) return;
        if (!notifList.length) { panel.innerHTML = '<div class="np-empty">Уведомлений нет.</div>'; return; }
        panel.innerHTML = notifList.map(n => `
            <div class="np-item ${n.status === 'NEW' ? 'unread' : ''}" data-id="${esc(n.notificationUid)}">
                <div class="np-title">${esc(n.title)}</div>
                <div class="np-meta">${esc(n.createdAt || '')}</div>
            </div>`).join('');
        panel.querySelectorAll('.np-item').forEach(el => {
            el.addEventListener('click', async () => {
                const id = el.getAttribute('data-id');
                try { await apiJson(`/api/v2/notifications/${id}/read`, { method: 'POST', body: {} }); } catch (_) { /* ignore */ }
                refreshNotifications();
            });
        });
    }

    function wireContextBar() {
        const toggle = document.getElementById('cb-mode-toggle');
        if (toggle) toggle.addEventListener('click', toggleWorkingMode);
        const sos = document.getElementById('cb-sos');
        if (sos) sos.addEventListener('click', sendSOS);
        const notif = document.getElementById('cb-notif');
        if (notif) notif.addEventListener('click', () => {
            const panel = document.getElementById('cb-notif-panel');
            if (panel) panel.hidden = !panel.hidden;
        });
        const guestReg = document.getElementById('guest-register-btn');
        if (guestReg) guestReg.addEventListener('click', startGuestRegistration);
    }

    function renderCitizenTerminal() {
        // Manual identifier entry + quick demo scenarios are demo-only: a real user scans with
        // the camera and never types an internal UID. Hidden unless admin / DEMO_MODE is on.
        const demo = demoToolsEnabled();
        document.getElementById('citizen-body').innerHTML = `
        <section class="panel panel-pad">
            <div class="section-title">Сканирование</div>
            <p class="muted" style="margin-bottom:14px">Отсканируйте QR-код объекта камерой${demo ? ' или введите идентификатор' : ''} — результат откроется на отдельной странице.</p>
            <div class="scan-actions">
                <button class="btn btn-primary" id="open-scanner" type="button">📷 Сканировать камерой</button>
            </div>
            ${demo ? `
            <div class="demo-hint"><span class="demo-tag">DEMO ONLY</span> Ручной ввод идентификатора — только для демонстрации. В реальном продукте доступно лишь сканирование камерой.</div>
            <div class="manual-row">
                <input id="manual-uid" type="text" placeholder="Идентификатор объекта, напр. CAR_TOYOTA_CAMRY">
                <button class="btn btn-ghost" id="manual-go" type="button">Проверить</button>
            </div>
            <div class="quick-label">Быстрые сценарии</div>
            <div class="quick-chips" id="quick-chips"></div>` : ''}
        </section>`;

        document.getElementById('open-scanner').addEventListener('click', openScanner);

        // Everything below is the demo-only manual block; only wire it when it was rendered.
        if (!demo) return;

        const quick = [
            { name: 'Кроссовки Nike Air Force 1', code: 'RETAIL_NIKE_AF1', tag: 'товар · конверсия гостя' },
            { name: 'Медкарта · Айдос', code: 'MED_RX_5521', tag: 'медицина · согласие пациента' },
            { name: 'Вынос мусора (услуга)', code: 'SERVICE_TRASH_PICKUP', tag: 'услуга · Request→Decision→Interaction' },
            { name: 'Toyota Camry 2024', code: 'CAR_TOYOTA_CAMRY', tag: 'авто · передача владельца' },
            { name: 'Умный замок · офис AITU', code: 'LOCK_OFFICE_AITU', tag: 'инфраструктура · доступ по роли' },
            { name: 'Студбилет AITU', code: 'DOC_STUDENT_AITU', tag: 'документ · образование' },
            { name: 'Единый QR · Айдос', code: 'IDENTITY:aaaaaaaa-0000-0000-0000-000000000007', tag: 'контекстный QR · данные зависят от вашей роли' }
        ];
        const chipBox = document.getElementById('quick-chips');
        chipBox.innerHTML = quick.map((q, i) => `
            <button class="quick-chip" type="button" data-i="${i}">
                <span class="qc-name">${esc(q.name)}</span>
                <span class="qc-code">${esc(q.code)}</span>
                <span class="qc-tag">${esc(q.tag)}</span>
            </button>`).join('');
        chipBox.querySelectorAll('.quick-chip').forEach(btn => {
            btn.addEventListener('click', () => {
                const q = quick[Number(btn.dataset.i)];
                document.getElementById('manual-uid').value = q.code;
                doScan(q.code);
            });
        });

        document.getElementById('manual-go').addEventListener('click', () => {
            const v = document.getElementById('manual-uid').value.trim();
            if (!v) { toast('Введите идентификатор объекта.', 'err'); return; }
            doScan(v);
        });
        document.getElementById('manual-uid').addEventListener('keydown', (e) => {
            if (e.key === 'Enter') { e.preventDefault(); document.getElementById('manual-go').click(); }
        });
    }

    async function doScan(objectUid) {
        const cleaned = normalizeScanInput(objectUid);
        // Result now opens as a full-screen page (Point 2) instead of the desktop sidebar, so
        // it reads as a real "scan result" screen on a phone. The owner-confirmation flow for
        // identity QRs is handled by the server and surfaced via polling below.
        const slot = openResultPage(inlineLoad('Проверка прав доступа и контекста…'));
        // No client-supplied time: the working-hours gate is evaluated server-side only (audit 4.3).
        try {
            const { ok, data } = await apiJson('/api/v2/scan', { method: 'POST', body: { objectUid: cleaned } });
            if (!data || (!ok && !data.outcome)) throw new Error((data && data.message) || 'Ошибка сканирования');
            renderScanResult(data, slot);
        } catch (err) {
            slot.innerHTML = `<div class="placeholder-box"><div class="pb-ico">⚠</div>
                <div class="pb-title">Ошибка</div><div class="pb-sub">${esc(err.message)}</div></div>`;
            toast(err.message, 'err');
        }
    }

    // Full-screen scan-result page (Point 2). Covers the viewport with a back button so the
    // result is a proper page on mobile, not a cramped sidebar that "ломает весь опыт".
    function openResultPage(innerHtml, barTitle) {
        let overlay = document.getElementById('result-overlay');
        if (!overlay) {
            overlay = document.createElement('div');
            overlay.id = 'result-overlay';
            overlay.className = 'result-overlay';
            document.body.appendChild(overlay);
        }
        overlay.innerHTML = `
            <div class="result-page">
                <div class="result-bar">
                    <button class="btn btn-ghost btn-sm result-back" id="result-back" type="button">← Назад</button>
                    <span class="result-bar-title">${esc(barTitle || 'Результат сканирования')}</span>
                    <span class="result-bar-spacer"></span>
                </div>
                <div class="result-body" id="result-body">${innerHtml}</div>
            </div>`;
        overlay.hidden = false;
        document.body.classList.add('no-scroll');
        document.getElementById('result-back').addEventListener('click', closeResultPage);
        overlay.scrollTop = 0;
        return document.getElementById('result-body');
    }

    function closeResultPage() {
        stopProfilePoll();
        const overlay = document.getElementById('result-overlay');
        if (overlay) overlay.hidden = true;
        document.body.classList.remove('no-scroll');
    }

    function renderScanResult(data, slot) {
        const result = slot || openResultPage('');
        const approved = data.outcome === 'APPROVED';
        let cardHtml = '';
        // Контекстный QR (Phase 2): визитка приходит с data при outcome=REVIEW (полный
        // профиль ждёт подтверждения владельца) — карточку показываем сразу.
        if (data.data && (approved || data.contextView === 'BUSINESS_CARD')) {
            cardHtml = renderContextCard(data);
        }
        const tierHtml = (approved && data.accessTier) ? accessTierHtml(data.accessTier) : '';
        const ctaHtml = data.registrationRequired ? guestCtaHtml(data.cta) : '';
        result.innerHTML = `
            ${contextRibbon(data)}
            ${verdictHtml(data.outcome, data.reason, data.riskLevel)}
            <div class="pipeline" id="scan-pipeline"></div>
            <div id="card-slot" class="mt-md">${tierHtml}${cardHtml}${ctaHtml}</div>`;
        animatePipeline(document.getElementById('scan-pipeline'), data, data.outcome);
        wireReportButtons();
        wirePrescriptions(data.objectUid, result);
        const ctaBtn = document.getElementById('guest-cta-btn');
        if (ctaBtn) ctaBtn.addEventListener('click', () => startGuestRegistration(data.objectUid));

        // Owner-Approval poll: a profile scan (IDENTITY) or a medical-card scan (MEDICAL) returns
        // REVIEW; poll until the owner/patient decides so the SCANNER finally sees the result.
        // For medical this is the P0 consent gate — the card opens only after the patient agrees.
        if (data.outcome === 'REVIEW' && data.interactionUid &&
            (data.category === 'IDENTITY' || data.category === 'MEDICAL')) {
            startProfilePoll(data.interactionUid, result, data.category === 'MEDICAL');
        }
    }

    // ---- Profile scan: poll for the owner's confirmation, then reveal the profile ----
    function stopProfilePoll() {
        if (profilePollTimer) { clearInterval(profilePollTimer); profilePollTimer = null; }
    }

    function startProfilePoll(interactionUid, container, medical) {
        stopProfilePoll();
        const slot = container.querySelector('#card-slot') || container;
        const note = document.createElement('div');
        note.className = 'await-banner';
        note.innerHTML = `<span class="await-dot"></span><span>${medical ? 'Ожидаем согласия пациента…' : 'Ожидаем подтверждения владельца…'}</span>`;
        slot.appendChild(note);
        let tries = 0;
        profilePollTimer = setInterval(async () => {
            if (++tries > 40) { stopProfilePoll(); note.querySelector('span:last-child').textContent = 'Время ожидания истекло.'; return; }
            try {
                const { ok, data } = await apiJson(`/api/v2/access/${interactionUid}/result`);
                if (!ok || !data) return;
                if (data.outcome === 'APPROVED') {
                    stopProfilePoll();
                    renderScanResult(data, container);
                    toast('Владелец подтвердил доступ к профилю.', 'ok');
                } else if (data.outcome === 'REJECTED') {
                    stopProfilePoll();
                    renderScanResult(data, container);
                }
            } catch (_) { /* keep polling */ }
        }, 3000);
    }

    // ---- Doctor → Pharmacist: prescription section on an approved medical card ----
    function prescriptionsSection(list, objectUid) {
        const isDoctor = currentUser && (currentUser.roles || []).includes('DOCTOR');
        const isPharma = currentUser && (currentUser.roles || []).includes('PHARMACIST');
        const rows = (list || []).map(p => {
            const dispensed = p.status === 'DISPENSED';
            const statusTag = dispensed
                ? '<span class="atag ok">Выдан</span>'
                : '<span class="atag review">Ожидает выдачи</span>';
            const dispenseBtn = (isPharma && !dispensed)
                ? `<button class="btn btn-primary btn-sm rx-dispense" type="button" data-rx="${esc(p.prescriptionUid)}">Выдать</button>`
                : '';
            const sub = [p.dose, p.schedule].filter(Boolean).join(' · ');
            const meta = [p.prescriber ? 'Врач: ' + p.prescriber : '', p.prescribedAt,
                dispensed && p.dispensedBy ? 'Выдал: ' + p.dispensedBy : ''].filter(Boolean).join(' · ');
            return `<div class="rx-row">
                <div class="rx-main"><div class="rx-name">${esc(p.name)}</div>
                    <div class="rx-sub">${esc(sub)}</div><div class="rx-meta">${esc(meta)}</div></div>
                <div class="rx-right">${statusTag}${dispenseBtn}</div>
            </div>`;
        }).join('');
        const doctorForm = isDoctor ? `
            <div class="rx-form">
                <div class="rx-form-row">
                    <input class="rx-name-in" type="text" placeholder="Препарат (напр. Амоксициллин)">
                    <input class="rx-dose-in" type="text" placeholder="Доза (500 мг)">
                </div>
                <input class="rx-sched-in" type="text" placeholder="Схема приёма (3 раза в день, 7 дней)">
                <button class="btn btn-gold btn-sm rx-add" type="button">+ Выписать рецепт</button>
            </div>` : '';
        const empty = (!list || list.length === 0) ? '<div class="obj-empty">Назначений пока нет.</div>' : '';
        return `<div class="dc-section rx-section">
            <h4>Назначения и рецепты</h4>${rows || empty}${doctorForm}</div>`;
    }

    function wirePrescriptions(objectUid, container) {
        if (!container || !objectUid) return;
        container.querySelectorAll('.rx-dispense').forEach(btn => {
            btn.addEventListener('click', async () => {
                btn.disabled = true; btn.textContent = 'Выдаём…';
                try {
                    const { ok, data } = await apiJson(`/api/v2/medical/prescriptions/${btn.dataset.rx}/dispense`,
                        { method: 'POST', body: {} });
                    if (!ok) throw new Error((data && data.message) || 'Не удалось выдать препарат.');
                    toast('Препарат выдан по рецепту.', 'ok');
                    refreshPrescriptions(objectUid, container);
                } catch (e) { toast(e.message, 'err'); btn.disabled = false; btn.textContent = 'Выдать'; }
            });
        });
        const form = container.querySelector('.rx-form');
        if (form) {
            const addBtn = form.querySelector('.rx-add');
            addBtn.addEventListener('click', async () => {
                const name = form.querySelector('.rx-name-in').value.trim();
                if (!name) { toast('Укажите препарат.', 'err'); return; }
                const body = { name,
                    dose: form.querySelector('.rx-dose-in').value.trim(),
                    schedule: form.querySelector('.rx-sched-in').value.trim() };
                addBtn.disabled = true; addBtn.textContent = 'Выписываем…';
                try {
                    const { ok, data } = await apiJson(`/api/v2/medical/${encodeURIComponent(objectUid)}/prescribe`,
                        { method: 'POST', body });
                    if (!ok) throw new Error((data && data.message) || 'Не удалось выписать рецепт.');
                    toast('Рецепт выписан. Фармацевт увидит его при сканировании.', 'ok');
                    refreshPrescriptions(objectUid, container);
                } catch (e) { toast(e.message, 'err'); addBtn.disabled = false; addBtn.textContent = '+ Выписать рецепт'; }
            });
        }
    }

    async function refreshPrescriptions(objectUid, container) {
        try {
            const { ok, data } = await apiJson(`/api/v2/medical/${encodeURIComponent(objectUid)}/prescriptions`);
            if (!ok || !Array.isArray(data)) return;
            const section = container.querySelector('.rx-section');
            if (!section) return;
            const wrap = document.createElement('div');
            wrap.innerHTML = prescriptionsSection(data, objectUid);
            section.replaceWith(wrap.firstElementChild);
            wirePrescriptions(objectUid, container);
        } catch (_) { /* ignore */ }
    }

    // Visibility tier (Scenario #1 / ПУБЛИЧНАЯ СТРАНИЦА): a guest receives only the
    // PUBLIC projection of a card; a registered identity gets the FULL card. The access
    // decision is identical for both — only the amount of data differs — so we label it
    // honestly rather than hiding the difference.
    function accessTierHtml(tier) {
        const pub = tier === 'PUBLIC';
        return `<div class="tier-ribbon ${pub ? 'public' : 'full'}">
            <span class="tr-ico">${pub ? '◐' : '●'}</span>
            <span>${pub ? 'Публичный просмотр — расширенные данные скрыты' : 'Полный доступ к карточке'}</span>
        </div>`;
    }

    function guestCtaHtml(text) {
        return `<div class="guest-cta">
            <div class="gc-glow"></div>
            <div class="gc-body">
                <div class="gc-title">Откройте полную карточку</div>
                <div class="gc-text">${esc(text || 'Для продолжения взаимодействия необходимо зарегистрироваться.')}</div>
                <ul class="gc-list">
                    <li>Цена, скидки и акции</li>
                    <li>Отзывы и рейтинг</li>
                    <li>История движения и поставщик</li>
                </ul>
            </div>
            <button class="btn btn-gold" id="guest-cta-btn" type="button">Зарегистрироваться</button>
        </div>`;
    }

    // Guest → registration WITHOUT a hard logout. We open a registration modal over the current
    // scan result — no jump to the auth screen, no torn-down context — switch the session in
    // place, fold in the guest history (maybeMergeGuest), then replay the scan so the user lands
    // back on the now-FULL card. resumeObjectUid remembers what they were viewing.
    function startGuestRegistration(resumeObjectUid) {
        const resume = (resumeObjectUid && typeof resumeObjectUid === 'string') ? resumeObjectUid : pendingScanTarget;
        openGuestRegisterModal(resume);
    }

    function openGuestRegisterModal(resumeObjectUid) {
        const body = `
            <p class="modal-text">Создайте аккаунт, чтобы сохранить историю и открыть полную карточку. Мы вернём вас к результату сканирования.</p>
            <div class="form-grid">
                <div class="form-row">
                    <div class="field"><label for="gr-firstName">Имя</label>
                        <input id="gr-firstName" type="text" placeholder="Имя"></div>
                    <div class="field"><label for="gr-lastName">Фамилия</label>
                        <input id="gr-lastName" type="text" placeholder="Фамилия"></div>
                </div>
                <div class="field"><label for="gr-username">Имя пользователя</label>
                    <input id="gr-username" type="text" autocomplete="username" placeholder="Латиница, от 3 символов"></div>
                <div class="field"><label for="gr-password">Пароль</label>
                    <input id="gr-password" type="password" autocomplete="new-password" placeholder="Не менее 12 символов, буквы и цифры"></div>
                <div class="field"><label for="gr-employment">Статус занятости</label>
                    <select id="gr-employment">
                        <option value="UNEMPLOYED" selected>Не трудоустроен(а)</option>
                        <option value="EMPLOYED">Трудоустроен(а)</option>
                    </select></div>
                <div class="field" id="gr-org-field" hidden>
                    <label for="gr-organization">Компания-работодатель</label>
                    <select id="gr-organization"><option value="">Выберите компанию…</option></select>
                    <div class="field-hint emp-hint">
                        Мы отправим заявку на трудоустройство администратору компании.
                        До подтверждения вы пользуетесь системой как гражданин.
                    </div>
                </div>
                <div class="field-error" id="gr-error"></div>
                <button class="btn btn-primary btn-block" id="gr-submit" type="button">Создать аккаунт</button>
            </div>`;
        showModal({
            title: 'Регистрация',
            bodyHtml: body,
            dismissValue: null,
            actions: [{ label: 'Позже', cls: 'btn-ghost', value: null }],
            onBody: (card, { close }) => {
                const errEl = card.querySelector('#gr-error');
                const submit = card.querySelector('#gr-submit');
                // Parity with the main registration form (audit FB-3): choosing «Трудоустроен(а)»
                // reveals the employer picker — otherwise the claim silently raises no request.
                const empSel = card.querySelector('#gr-employment');
                const orgField = card.querySelector('#gr-org-field');
                const orgSel = card.querySelector('#gr-organization');
                empSel.addEventListener('change', () => {
                    const employed = empSel.value === 'EMPLOYED';
                    orgField.hidden = !employed;
                    if (employed) fillOrganizationSelect(orgSel);
                });
                submit.addEventListener('click', async () => {
                    errEl.textContent = '';
                    const payload = {
                        firstName: card.querySelector('#gr-firstName').value.trim(),
                        lastName: card.querySelector('#gr-lastName').value.trim(),
                        username: card.querySelector('#gr-username').value.trim(),
                        password: card.querySelector('#gr-password').value,
                        employmentStatus: card.querySelector('#gr-employment').value,
                        // Self-registration is always CITIZEN; the server enforces this regardless.
                        profession: 'CITIZEN'
                    };
                    // An employed applicant may name an employer — raises a verification request.
                    if (payload.employmentStatus === 'EMPLOYED' && orgSel.value) {
                        payload.organizationUid = orgSel.value;
                    }
                    if (!payload.firstName || !payload.lastName || !payload.username || !payload.password) {
                        errEl.textContent = 'Заполните все поля.'; return;
                    }
                    submit.disabled = true; submit.textContent = 'Создаём…';
                    try {
                        const { ok, status, data } = await apiJson('/api/auth/register', { method: 'POST', body: payload });
                        if (!ok) {
                            let msg = (data && data.message) || 'Не удалось зарегистрироваться';
                            if (status === 409) msg = (data && data.message) || 'Имя пользователя уже занято';
                            if (data && data.details) { const f = Object.values(data.details)[0]; if (f) msg = f; }
                            throw new Error(msg);
                        }
                        // Switch the session from guest to the new citizen IN PLACE — no visible logout.
                        await doLogin(payload.username, payload.password);
                        await maybeMergeGuest();
                        close(null);
                        toast('Аккаунт создан. История гостя сохранена.', 'ok');
                        // Re-render as the now-registered citizen and replay the scan so they land
                        // back on the FULL card instead of an empty profile.
                        if (resumeObjectUid) pendingScanTarget = resumeObjectUid;
                        route();
                        consumePendingScan();
                    } catch (err) {
                        errEl.textContent = err.message;
                        submit.disabled = false; submit.textContent = 'Создать аккаунт';
                    }
                });
            }
        });
    }

    function renderCitizenAudit() {
        document.getElementById('citizen-body').innerHTML = `
        <section class="panel panel-pad">
            <div class="audit-head">
                <div class="section-title" style="margin-bottom:0">Мои действия (неизменяемый журнал)</div>
                <button class="btn btn-ghost btn-sm" id="audit-refresh" type="button">Обновить</button>
            </div>
            <div class="table-scroll">
                <table class="audit-tbl">
                    <thead><tr><th>Событие</th><th>Статус</th><th>Объект</th><th>ID записи</th><th>Время</th></tr></thead>
                    <tbody id="audit-body"><tr><td colspan="5" class="empty">Загрузка…</td></tr></tbody>
                </table>
            </div>
            <div id="audit-pager"></div>
        </section>`;
        document.getElementById('audit-refresh').addEventListener('click', () => loadAudit('/api/v2/audit/me'));
        loadAudit('/api/v2/audit/me');
    }

    // -------------------------------------------------------------
    //  Citizen: incoming access requests ("Подтвердить доступ")
    // -------------------------------------------------------------
    const INTERACTION_RU = {
        SCAN: 'Скан объекта', PROFILE_SCAN: 'Скан профиля', MEDICAL_SCAN: 'Запрос к медкарте',
        REPORT: 'Обращение', SOS: 'SOS', QR_CREATION: 'Создание QR',
        PRESCRIPTION: 'Рецепт', SERVICE_ORDER: 'Заявка на услугу'
    };
    const ISTATUS_RU = { PENDING: 'Ожидает', CONFIRMED: 'Подтверждено', REJECTED: 'Отклонено' };
    const COMPLAINT_RU = { NEW: 'Новая', IN_PROGRESS: 'В работе', RESOLVED: 'Решена', REJECTED: 'Отклонена' };

    async function loadAccessRequests() {
        const box = document.getElementById('access-requests');
        if (!box) return;
        try {
            const { ok, data } = await apiJson('/api/v2/access/pending');
            if (!ok || !Array.isArray(data)) return;
            if (data.length === 0) { lastAccessSig = ''; box.innerHTML = ''; return; }
            // Fingerprint the list so the background poll repaints only on real changes —
            // a mid-click repaint would otherwise swallow the owner's «Подтвердить» tap.
            const sig = data.map(r => r.interactionUid).join('|');
            if (sig === lastAccessSig && box.childElementCount) return;
            const prev = lastAccessSig ? lastAccessSig.split('|') : [];
            const fresh = lastAccessSig !== null ? data.find(r => !prev.includes(r.interactionUid)) : null;
            lastAccessSig = sig;
            box.innerHTML = `
                <div class="access-head">🔔 Запросы на доступ к вашим данным</div>
                ${data.map(r => `
                    <div class="access-row ${r.kind === 'MEDICAL' ? 'medical' : ''}" data-id="${esc(r.interactionUid)}">
                        <div class="access-meta"><strong>${esc(r.fromName)}</strong>
                            <span>${r.kind === 'MEDICAL' ? '🩺 ' : '👤 '}${esc(r.what || 'доступ к профилю')}${r.createdAt ? ' · ' + esc(r.createdAt) : ''}</span></div>
                        <div class="access-actions">
                            <button class="btn btn-primary btn-sm acc-confirm" type="button">Подтвердить</button>
                            <button class="btn btn-ghost btn-sm acc-reject" type="button">Отклонить</button>
                        </div>
                    </div>`).join('')}`;
            box.querySelectorAll('.access-row').forEach(row => {
                const id = row.getAttribute('data-id');
                row.querySelector('.acc-confirm').addEventListener('click', () => decideAccess(id, 'confirm'));
                row.querySelector('.acc-reject').addEventListener('click', () => decideAccess(id, 'reject'));
            });
            if (fresh) {
                // «Магия без F5» (P0): свежий запрос сам открывает окно согласия. Если какая-то
                // модалка уже на экране (SOS, жалоба…) — не перебиваем, ограничиваемся тостом:
                // запрос в любом случае уже лежит в списке выше.
                if (document.querySelector('.modal-overlay')) toast('Новый запрос на доступ к вашим данным.', 'info');
                else openConsentPopup(fresh);
            }
        } catch (_) { /* transient failure — keep the current list on screen */ }
    }

    // Входящее согласие как всплывающее окно: врач сканирует — у пациента само открывается
    // «Подтвердить / Отклонить». «Позже» ничего не решает: запрос остаётся в списке на главной.
    async function openConsentPopup(r) {
        const medical = r.kind === 'MEDICAL';
        const action = await showModal({
            title: medical ? '🩺 Запрос к медицинской карте' : '🔔 Запрос доступа',
            bodyHtml: `
                <p class="modal-text"><b>${esc(r.fromName)}</b> запрашивает
                    ${medical ? 'доступ к вашей медицинской карте' : esc(r.what || 'доступ к профилю')}${r.createdAt ? ' · ' + esc(r.createdAt) : ''}.</p>
                <p class="muted">Решение фиксируется в неизменяемой истории. «Позже» оставит запрос в списке на главной странице.</p>`,
            dismissValue: null,
            actions: [
                { label: 'Позже', cls: 'btn-ghost', value: null },
                { label: 'Отклонить', cls: 'btn-ghost', value: 'reject' },
                { label: 'Подтвердить', cls: 'btn-primary', value: 'confirm' }
            ]
        });
        if (action) decideAccess(r.interactionUid, action);
    }

    // FB-1: the scanner side polls for the verdict, so the owner/patient side must refresh
    // too — otherwise the flagship consent flow stalls until a manual F5. One shared ticker
    // for the citizen view; it skips background tabs and tears itself down elsewhere.
    function startAccessPolling() {
        if (accessPollTimer) return;
        accessPollTick = 0;
        accessPollTimer = setInterval(() => {
            if (!currentUser || currentUser.admin) { stopAccessPolling(); return; }
            if (document.hidden || !document.getElementById('access-requests')) return;
            loadAccessRequests();
            // Notifications (complaint replies, consent outcomes) ride along every 3rd tick.
            if (++accessPollTick % 3 === 0) refreshNotifications();
        }, 4000);
    }

    function stopAccessPolling() {
        if (accessPollTimer) { clearInterval(accessPollTimer); accessPollTimer = null; }
    }

    async function decideAccess(interactionUid, action) {
        try {
            const { ok, data } = await apiJson(`/api/v2/access/${interactionUid}/${action}`,
                { method: 'POST', body: {} });
            if (ok) {
                // Message follows the object type (medical card vs personal profile) reported by
                // the server, so confirming a medical request no longer says "профиль".
                const target = (data && data.category === 'MEDICAL') ? 'к медицинской карте' : 'к профилю';
                const okMsg = action === 'confirm' ? `Доступ ${target} предоставлен.` : 'Доступ отклонён.';
                toast(okMsg, action === 'confirm' ? 'ok' : 'info');
            } else toast((data && data.reason) || 'Не удалось обработать запрос.', 'err');
        } catch (_) { toast('Ошибка обработки запроса.', 'err'); }
        loadAccessRequests();
        refreshNotifications();
    }

    // -------------------------------------------------------------
    //  Citizen: "Мой QR" + demonstration page
    // -------------------------------------------------------------
    function renderCitizenMyQr() {
        const body = document.getElementById('citizen-body');
        body.innerHTML = `<section class="panel panel-pad">${inlineLoad('Загрузка QR…')}</section>`;
        apiJson('/api/v2/my-qr').then(({ ok, data }) => {
            if (!ok || !data) { body.innerHTML = `<section class="panel panel-pad"><div class="obj-empty">Не удалось загрузить QR.</div></section>`; return; }
            const initials = (((currentUser.firstName || '')[0] || '') + ((currentUser.lastName || '')[0] || '')).toUpperCase();
            body.innerHTML = `
            <section class="panel panel-pad myqr-card">
                <div class="section-title">Мой QR</div>
                <div class="myqr-body">
                    <div class="myqr-avatar">${esc(initials)}</div>
                    <div class="myqr-name">${esc(data.fullName)}</div>
                    <div class="myqr-sub">Постоянный идентификатор личности</div>
                    <img class="myqr-img" src="${esc(data.qrImageDataUri)}" alt="Мой QR-код">
                    <div class="qr-uid">${esc(shortId(data.identityUid))}</div>
                    <button class="btn btn-primary btn-block" id="qr-demo-btn" type="button">Демонстрировать</button>
                    <button class="btn btn-ghost btn-block" id="qr-preview-btn" type="button" style="margin-top:8px">Как видят мою визитку</button>
                    <p class="muted" style="margin-top:10px">QR — только идентификатор. Он не несёт ролей, прав или доверия.</p>
                </div>
            </section>`;
            document.getElementById('qr-demo-btn').addEventListener('click', () => openQrDemo(data.fullName, data.qrImageDataUri));
            document.getElementById('qr-preview-btn').addEventListener('click', openProfilePreview);
        });
    }

    // P2 из ZAKAZDAR-аудита («Демонстрировать профиль»): владелец видит ровно ту визитку,
    // которую другой пользователь получит после его согласия, — СВОЙ vcard-payload рендерится
    // тем же businessCard(), что и на стороне сканирующего. Ноль новых рендереров.
    async function openProfilePreview() {
        const { ok, data } = await apiJson('/api/v2/dossier/me');
        if (!ok || !data || !data.available || !data.vcard) {
            toast('Не удалось загрузить визитку.', 'err');
            return;
        }
        const d = Object.assign({}, data.vcard, {
            fullProfile: true,
            trustLevel: currentUser.trustLevel,
            riskScore: currentUser.riskScore
        });
        showModal({
            title: 'Так вашу визитку видят другие',
            bodyHtml: `${businessCard(d)}
                <p class="muted" style="margin-top:10px">Публичная часть открыта любому зарегистрированному
                гражданину сразу после сканирования; «закрытая часть» — только после вашего подтверждения.</p>`,
            dismissValue: null,
            actions: [{ label: 'Понятно', cls: 'btn-primary', value: true }]
        });
    }

    function openQrDemo(name, dataUri) {
        let overlay = document.getElementById('qr-demo-overlay');
        if (!overlay) {
            overlay = document.createElement('div');
            overlay.id = 'qr-demo-overlay';
            overlay.className = 'qr-demo-overlay';
            document.body.appendChild(overlay);
        }
        overlay.innerHTML = `
            <div class="qr-demo-inner">
                <img class="qr-demo-img" src="${esc(dataUri)}" alt="QR-код">
                <div class="qr-demo-name">${esc(name)}</div>
                <button class="btn btn-ghost qr-demo-back" id="qr-demo-back" type="button">← Назад</button>
            </div>`;
        overlay.hidden = false;
        document.getElementById('qr-demo-back').addEventListener('click', () => { overlay.hidden = true; });
    }

    // -------------------------------------------------------------
    //  Citizen: "Моя история" (я сканировал / кто сканировал меня)
    // -------------------------------------------------------------
    function renderCitizenHistory() {
        const body = document.getElementById('citizen-body');
        body.innerHTML = `<section class="panel panel-pad">${inlineLoad('Загрузка истории…')}</section>`;
        apiJson('/api/v2/history/me').then(({ ok, data }) => {
            if (!ok || !data) { body.innerHTML = `<section class="panel panel-pad"><div class="obj-empty">Не удалось загрузить историю.</div></section>`; return; }
            body.innerHTML = `
            <div class="split split-wide">
                <section class="panel panel-pad">
                    <div class="section-title">Я сканировал</div>
                    ${historyTable(data.scannedByMe, true)}
                </section>
                <section class="panel panel-pad">
                    <div class="section-title">Кто сканировал меня</div>
                    ${historyTable(data.scansOfMe, false)}
                </section>
            </div>
            <p class="muted" style="margin-top:12px">Это ваша личная история. Полный аудит системы доступен только администратору.</p>`;
            body.querySelectorAll('.hist-complain').forEach(btn => {
                btn.addEventListener('click', () => {
                    complaintPrefill = btn.getAttribute('data-id');
                    citizenTab = 'complaints';
                    renderCitizen();
                });
            });
        });
    }

    function historyTable(rows, allowComplain) {
        if (!Array.isArray(rows) || rows.length === 0) {
            return `<div class="obj-empty">Записей пока нет.</div>`;
        }
        return `<div class="table-scroll"><table class="audit-tbl">
            <thead><tr><th>Имя / объект</th><th>Тип</th><th>Статус</th><th>Дата</th>${allowComplain ? '<th></th>' : ''}</tr></thead>
            <tbody>${rows.map(r => `
                <tr>
                    <td>${esc(r.name)}</td>
                    <td>${esc(INTERACTION_RU[r.type] || r.type)}</td>
                    <td>${esc(ISTATUS_RU[r.status] || r.status || '—')}</td>
                    <td class="ts">${esc(r.createdAt || '—')}</td>
                    ${allowComplain ? `<td><button class="btn btn-ghost btn-sm hist-complain" type="button" data-id="${esc(r.interactionUid)}">Пожаловаться</button></td>` : ''}
                </tr>`).join('')}</tbody>
        </table></div>`;
    }

    // -------------------------------------------------------------
    //  Citizen: "Жалобы"
    // -------------------------------------------------------------
    function renderCitizenComplaints() {
        const body = document.getElementById('citizen-body');
        body.innerHTML = `<section class="panel panel-pad">${inlineLoad('Загрузка…')}</section>`;
        Promise.all([apiJson('/api/v2/history/me'), apiJson('/api/v2/complaints/me')]).then(([hist, mine]) => {
            const interactions = [];
            if (hist.ok && hist.data) {
                (hist.data.scannedByMe || []).forEach(r => interactions.push(r));
                (hist.data.scansOfMe || []).forEach(r => interactions.push(r));
            }
            // P2: a general complaint needs no interaction. Offer it as the default option;
            // a specific interaction can still be chosen (or is pre-selected from history).
            const generalOpt = `<option value="" ${complaintPrefill ? '' : 'selected'}>Общая жалоба (без привязки)</option>`;
            const options = interactions.map(r =>
                `<option value="${esc(r.interactionUid)}" ${complaintPrefill === r.interactionUid ? 'selected' : ''}>${esc((INTERACTION_RU[r.type] || r.type) + ' · ' + r.name + ' · ' + (r.createdAt || ''))}</option>`).join('');
            const list = (mine.ok && Array.isArray(mine.data)) ? mine.data : [];
            body.innerHTML = `
            <div class="split split-wide">
                <section class="panel panel-pad">
                    <div class="section-title">Подать жалобу</div>
                    <form id="complaint-form" class="form-grid">
                        <div class="field">
                            <label for="cmp-interaction">Взаимодействие (необязательно)</label>
                            <select id="cmp-interaction">${generalOpt}${options}</select>
                            <div class="field-hint">Можно подать общую жалобу или выбрать конкретное взаимодействие из истории.</div>
                        </div>
                        <div class="field">
                            <label for="cmp-subject">Тема</label>
                            <input id="cmp-subject" type="text" maxlength="200" placeholder="Кратко о проблеме">
                        </div>
                        <div class="field">
                            <label for="cmp-category">Категория</label>
                            <select id="cmp-category">
                                <option value="ОБЩАЯ">Общая</option>
                                <option value="ДОСТУП">Доступ к данным</option>
                                <option value="КАЧЕСТВО">Качество услуги</option>
                                <option value="БЕЗОПАСНОСТЬ">Безопасность</option>
                            </select>
                        </div>
                        <div class="field">
                            <label for="cmp-desc">Описание</label>
                            <textarea id="cmp-desc" maxlength="1000" placeholder="Подробности"></textarea>
                        </div>
                        <div class="field-error" id="cmp-error"></div>
                        <button class="btn btn-primary btn-block" id="cmp-submit" type="submit">Отправить жалобу</button>
                    </form>
                </section>
                <section class="panel panel-pad">
                    <div class="section-title">Мои жалобы</div>
                    <div id="cmp-list">${complaintList(list)}</div>
                </section>
            </div>`;
            complaintPrefill = null;
            document.getElementById('complaint-form').addEventListener('submit', async (e) => {
                e.preventDefault();
                const errEl = document.getElementById('cmp-error');
                errEl.textContent = '';
                const interactionUid = document.getElementById('cmp-interaction').value;
                const subject = document.getElementById('cmp-subject').value.trim();
                if (!subject) { errEl.textContent = 'Укажите тему жалобы.'; return; }
                const payload = { subject,
                    category: document.getElementById('cmp-category').value,
                    description: document.getElementById('cmp-desc').value.trim() };
                if (interactionUid) payload.interactionUid = interactionUid;
                const btn = document.getElementById('cmp-submit');
                btn.disabled = true; btn.textContent = 'Отправка…';
                try {
                    const { ok, data } = await apiJson('/api/v2/complaints', { method: 'POST', body: payload });
                    if (!ok) throw new Error((data && data.message) || 'Не удалось отправить жалобу');
                    toast('Жалоба зарегистрирована.', 'ok');
                    renderCitizenComplaints();
                } catch (err) {
                    errEl.textContent = err.message;
                    btn.disabled = false; btn.textContent = 'Отправить жалобу';
                }
            });
        });
    }

    function complaintList(list) {
        if (!list.length) return `<div class="obj-empty">Жалоб пока нет.</div>`;
        return list.map(c => `
            <div class="cmp-item">
                <div class="cmp-top">
                    <span class="cmp-subject">${esc(c.subject)}</span>
                    <span class="cmp-status s-${esc((c.status || '').toLowerCase())}">${esc(COMPLAINT_RU[c.status] || c.status)}</span>
                </div>
                <div class="cmp-meta">${esc(c.category)} · ${esc(c.createdAt || '')}</div>
                ${c.description ? `<div class="cmp-desc">${esc(c.description)}</div>` : ''}
            </div>`).join('');
    }

    // -------------------------------------------------------------
    //  Shared: audit table loader
    // -------------------------------------------------------------
    async function loadAudit(path, page = 0) {
        const body = document.getElementById('audit-body');
        if (!body) return;
        try {
            const sep = path.includes('?') ? '&' : '?';
            const { ok, data } = await apiJson(`${path}${sep}page=${page}&size=50`);
            if (!ok || !data || !Array.isArray(data.content)) {
                body.innerHTML = `<tr><td colspan="5" class="empty">Не удалось загрузить журнал.</td></tr>`; return;
            }
            const rows = data.content;
            body.innerHTML = rows.length === 0
                ? `<tr><td colspan="5" class="empty">Событий пока нет.</td></tr>`
                : rows.map(h => `
                <tr>
                    <td class="evt" data-label="Событие">${esc(EVENT_RU[h.eventType] || h.eventType || 'Событие')}</td>
                    <td data-label="Статус">${eventTag(h.eventType)}</td>
                    <td class="mono" data-label="Объект">${esc(h.objectUid || '—')}</td>
                    <td class="uuid" data-label="ID записи">${esc(shortId(h.historyUid))}</td>
                    <td class="ts" data-label="Время">${esc(h.createdAt || '—')}</td>
                </tr>`).join('');
            const pager = document.getElementById('audit-pager');
            if (pager) {
                pager.innerHTML = pagerHtml(data);
                bindPager(pager, data, p => loadAudit(path, p));
            }
        } catch (_) {
            body.innerHTML = `<tr><td colspan="5" class="empty">Ошибка подключения к журналу.</td></tr>`;
        }
    }

    // -------------------------------------------------------------
    //  Shared: verdict + pipeline
    // -------------------------------------------------------------
    // `titleOverride` replaces the access-verdict headline for flows that are not access
    // decisions at all (SOS, issue reports) — audit FB-6: «Доступ разрешён» on an SOS is absurd.
    function verdictHtml(outcome, reason, riskLevel, titleOverride) {
        const map = {
            APPROVED: { cls: 'ok', ico: '✓', title: 'Доступ разрешён' },
            REJECTED: { cls: 'reject', ico: '✕', title: 'Доступ запрещён' },
            REVIEW: { cls: 'review', ico: '?', title: 'Требуется проверка' }
        };
        const v = map[outcome] || map.REVIEW;
        const risk = riskLevel ? `<span class="risk-badge risk-${esc(riskLevel)}">Риск: ${esc(riskLevel)}</span>` : '';
        return `
            <div class="verdict ${v.cls}">
                <div class="v-ico">${v.ico}</div>
                <div class="v-body">
                    <div class="v-title">${esc(titleOverride || v.title)}</div>
                    <div class="v-reason">${esc(reason)}</div>
                </div>
                ${risk}
            </div>`;
    }

    function animatePipeline(container, chain, outcome, instant) {
        if (!container) return;
        const steps = [
            { name: 'Личность', uid: chain.identityUid },
            { name: 'Запрос', uid: chain.requestUid },
            { name: 'Решение', uid: chain.decisionUid },
            { name: 'Действие', uid: chain.interactionUid },
            { name: 'История', uid: chain.historyUid }
        ];
        const rejected = outcome && outcome !== 'APPROVED';
        container.innerHTML = `<div class="pl-track">${steps.map((s, i) => {
            let dot = String(i + 1);
            if (rejected && i === 2) dot = '✕';
            else if (!rejected && i === 4) dot = '✓';
            return `<div class="pl-step" data-i="${i}">
                <div class="pl-dot">${dot}</div>
                <div class="pl-name">${esc(s.name)}</div>
                <div class="pl-uid">${s.uid ? esc(shortId(s.uid)) : '—'}</div>
            </div>`;
        }).join('')}</div>`;

        const els = container.querySelectorAll('.pl-step');
        const lightStep = (el, i) => {
            el.classList.add('lit');
            if (rejected && i === 2) el.classList.add('reject');
            else if (!rejected && i === 4) el.classList.add('done');
        };
        if (instant) { els.forEach(lightStep); return; }
        els.forEach((el, i) => setTimeout(() => lightStep(el, i), 170 * (i + 1)));
    }

    // -------------------------------------------------------------
    //  Shared: cards
    // -------------------------------------------------------------
    function renderCardByCategory(category, d, objectUid) {
        switch (category) {
            case 'MEDICAL': return medicalCard(d, objectUid);
            case 'RETAIL': return retailCard(d, objectUid);
            case 'ECO': return ecoCard(d, objectUid);
            case 'INFRASTRUCTURE': return infraCard(d, objectUid);
            case 'LEGAL': return legalCard(d, objectUid);
            default: return generalCard(d, objectUid);
        }
    }

    // -------------------------------------------------------------
    //  Phase 2 — контекстный QR: карточка подбирается по представлению,
    //  которое вернул сервер для роли сканирующего.
    // -------------------------------------------------------------
    function renderContextCard(res) {
        const d = res.data || {};
        if (res.contextView === 'BUSINESS_CARD' || res.category === 'IDENTITY') return businessCard(d);
        if (res.contextView === 'PRESCRIPTIONS') return rxOnlyCard(d, res.objectUid);
        return renderCardByCategory(res.category, d, res.objectUid);
    }

    /** Полоска контекста: кто сканирует — то и открывается (ключевой месседж демо). */
    function contextRibbon(res) {
        const map = {
            MEDICAL: { ico: '🩺', txt: 'Контекст: медицинский работник → медицинская карта (по согласию пациента)' },
            PRESCRIPTIONS: { ico: '💊', txt: 'Контекст: фармацевт → только рецепты (минимальный доступ по роли)' },
            LEGAL: { ico: '👮', txt: 'Контекст: полиция при исполнении → правовой статус' },
            BUSINESS_CARD: { ico: '👤', txt: 'Контекст: гражданин → цифровая визитка' }
        };
        const v = map[res.contextView];
        if (!v) return '';
        return `<div class="ctx-ribbon ctx-${esc((res.contextView || '').toLowerCase())}">
            <span class="cr-ico">${v.ico}</span>
            <span class="cr-txt">${esc(v.txt)}</span>
            ${res.subjectName ? `<span class="cr-subject">${esc(res.subjectName)}</span>` : ''}
        </div>`;
    }

    /** Цифровая визитка (единый QR, скан гражданином). fullProfile ⇒ раскрыта закрытая часть. */
    function businessCard(d) {
        const name = d.fullName || d.title || 'Пользователь';
        const initials = name.split(/\s+/).map(w => w[0] || '').join('').substring(0, 2).toUpperCase();
        const chips = [
            d.phone ? { ico: '📞', v: d.phone } : null,
            d.telegram ? { ico: '✈️', v: d.telegram } : null,
            d.email ? { ico: '✉️', v: d.email } : null,
            d.city ? { ico: '📍', v: d.city } : null
        ].filter(Boolean).map(c =>
            `<span class="vc-chip"><span>${c.ico}</span>${esc(c.v)}</span>`).join('');
        const full = !!d.fullProfile;
        const fullBlock = full ? `
            <div class="vc-full">
                <div class="vc-full-head">🔓 Закрытая часть — раскрыта владельцем</div>
                <div class="kv-grid">
                    ${kv('Уровень доверия', (d.trustLevel != null ? d.trustLevel + ' / 100' : '—'))}
                    ${kv('Риск', d.riskScore || 'NORMAL')}
                    ${d.profession ? kv('Профессия', professionRu(d.profession)) : ''}
                </div>
            </div>` : (d.trustLevel != null ? `
            <div class="vc-trust" title="Единая метрика доверия платформы">
                <span class="vc-trust-k">Доверие</span><span class="vc-trust-v">${esc(d.trustLevel)} / 100</span>
            </div>` : '');
        return `
        <div class="card data-card vcard fade-in">
            <div class="vc-top">
                <div class="vc-avatar">${esc(initials)}</div>
                <div class="vc-id">
                    <div class="vc-name">${esc(name)}</div>
                    <div class="vc-sub">${esc(d.profession ? professionRu(d.profession) : (d.about || 'Цифровая визитка'))}</div>
                </div>
                <span class="vc-mark" aria-hidden="true">▦</span>
            </div>
            ${chips ? `<div class="vc-chips">${chips}</div>` : ''}
            ${d.about && d.profession ? `<div class="dc-section"><p class="muted">${esc(d.about)}</p></div>` : ''}
            ${fullBlock}
            ${d.note ? `<div class="dc-section vc-note"><p class="muted">${esc(d.note)}</p></div>` : ''}
        </div>`;
    }

    /** Правовое досье (скан полицейским): статус, штрафы, розыск. */
    function legalCard(d, objectUid) {
        const cr = d.criminalRecord || {};
        const fines = Array.isArray(d.fines) ? d.fines : [];
        const dl = d.drivingLicense || null;
        const fineRows = fines.map(f => `
            <tr>
                <td class="mono">${esc(f.date || '—')}</td>
                <td>${esc(f.article || '—')}</td>
                <td class="mono">${esc(fmtPrice(f.amount, '₸'))}</td>
                <td>${f.status === 'ОПЛАЧЕН'
                    ? '<span class="atag ok">Оплачен</span>'
                    : '<span class="atag bad">Не оплачен</span>'}</td>
            </tr>`).join('');
        return `
        <div class="card data-card legal-card fade-in">
            ${cardHead(d.fullName || 'Гражданин', 'ИИН: ' + (d.iin || '—'), 'LEGAL')}
            <div class="legal-status">
                <div class="ls-main ok">
                    <span class="ls-ico">✓</span>
                    <div><div class="ls-title">${esc(cr.status || 'НЕ СУДИМ(А)')}</div>
                    <div class="ls-sub">Справка ${esc(cr.certificateNo || '—')} · ${esc(cr.source || '')}</div></div>
                </div>
                <div class="ls-wanted">${esc(d.wanted || 'В розыске не числится')}</div>
            </div>
            <div class="dc-section">
                <div class="kv-grid">
                    ${kv('Дата рождения', d.birthDate || '—', true)}
                    ${kv('Адрес регистрации', d.address || '—')}
                    ${dl ? kv('Вод. удостоверение', dl.number + ' · кат. ' + dl.categories, true) : ''}
                    ${dl ? kv('Действительно до', dl.validTill, true) : ''}
                </div>
            </div>
            <div class="dc-section"><h4>Административные штрафы</h4>
                ${fines.length ? `<table class="tbl"><thead><tr><th>Дата</th><th>Статья</th><th>Сумма</th><th>Статус</th></tr></thead>
                <tbody>${fineRows}</tbody></table>` : '<div class="obj-empty">Штрафов нет — чистая история.</div>'}
            </div>
            ${d.restrictions ? `<div class="dc-section">${kv('Ограничения', d.restrictions)}</div>` : ''}
            ${d.note ? `<div class="dc-section"><p class="muted">${esc(d.note)}</p></div>` : ''}
        </div>`;
    }

    /** Срез «только рецепты» (скан фармацевтом): полная карта не раскрывается. */
    function rxOnlyCard(d, objectUid) {
        return `
        <div class="card data-card rx-only fade-in">
            ${cardHead(d.patientName || 'Пациент', 'ID: ' + (d.patientId || objectUid), 'MEDICAL')}
            ${d.scope ? `<div class="rx-scope"><span>🔒</span>${esc(d.scope)}</div>` : ''}
            ${prescriptionsSection(d.prescriptions, objectUid)}
        </div>`;
    }

    function cardHead(title, sub, category) {
        const labels = { MEDICAL: 'Медицина', RETAIL: 'Товар', ECO: 'Экология', INFRASTRUCTURE: 'Инфраструктура', LEGAL: 'Правовой статус', GENERAL: 'Объект' };
        const cls = (category || 'general').toLowerCase();
        return `
            <div class="dc-head">
                <div>
                    <div class="dc-title">${esc(title)}</div>
                    <div class="dc-sub">${esc(sub)}</div>
                </div>
                <span class="dc-cat ${cls}">${esc(labels[category] || 'Объект')}</span>
            </div>`;
    }

    function kv(k, v, mono) {
        return `<div class="kv"><div class="kv-k">${esc(k)}</div><div class="kv-v ${mono ? 'mono' : ''}">${esc(v)}</div></div>`;
    }

    function medicalCard(d, objectUid) {
        const allergies = Array.isArray(d.allergies) ? d.allergies : [];
        const chronic = Array.isArray(d.chronicConditions) ? d.chronicConditions : [];
        const meds = Array.isArray(d.medications) ? d.medications : [];
        const visits = Array.isArray(d.recentVisits) ? d.recentVisits : [];
        const imm = Array.isArray(d.immunizations) ? d.immunizations : [];
        const vitals = d.vitals && Array.isArray(d.vitals.series) ? d.vitals.series : [];

        const allergyBanner = allergies.length ? `
            <div class="alert-banner">
                <div class="ab-ico">⚠</div>
                <div class="ab-txt"><strong>Аллергии и противопоказания</strong>
                <span>${esc(allergies.join(', '))}</span></div>
            </div>` : '';
        const medRows = meds.map(m => `<tr><td>${esc(m.name)}</td><td>${esc(m.dose)}</td><td>${esc(m.schedule)}</td></tr>`).join('');
        const visitRows = visits.map(v => `<tr><td class="mono">${esc(v.date)}</td><td>${esc(v.clinic)}</td><td>${esc(v.reason)}</td><td>${esc(v.doctor)}</td></tr>`).join('');

        return `
        <div class="card data-card fade-in">
            ${cardHead(d.patientName || 'Пациент', 'ID: ' + (d.patientId || objectUid), 'MEDICAL')}
            ${allergyBanner}
            <div class="dc-section">
                <div class="kv-grid">
                    ${kv('Возраст', d.age != null ? d.age : '—')}
                    ${kv('Пол', d.gender || '—')}
                    ${kv('Группа крови', d.bloodType || '—')}
                    ${kv('ИИН', d.iinMasked || '—', true)}
                </div>
            </div>
            ${chronic.length ? `<div class="dc-section"><h4>Хронические состояния</h4>
                <div class="tag-list">${chronic.map(c => `<span class="tag chronic">${esc(c)}</span>`).join('')}</div></div>` : ''}
            ${meds.length ? `<div class="dc-section"><h4>Назначенные препараты</h4>
                <table class="tbl"><thead><tr><th>Препарат</th><th>Доза</th><th>Приём</th></tr></thead><tbody>${medRows}</tbody></table></div>` : ''}
            ${vitals.length ? `<div class="dc-section"><h4>Динамика давления и пульса</h4>
                <div class="chart-wrap">${vitalsChart(vitals)}
                <div class="chart-legend">
                    <span class="legend-item"><span class="legend-swatch vc-k-systolic"></span>Систолическое</span>
                    <span class="legend-item"><span class="legend-swatch vc-k-diastolic"></span>Диастолическое</span>
                    <span class="legend-item"><span class="legend-swatch vc-k-pulse"></span>Пульс</span>
                </div></div></div>` : ''}
            ${visits.length ? `<div class="dc-section"><h4>История посещений</h4>
                <table class="tbl"><thead><tr><th>Дата</th><th>Клиника</th><th>Причина</th><th>Врач</th></tr></thead><tbody>${visitRows}</tbody></table></div>` : ''}
            ${imm.length ? `<div class="dc-section"><h4>Вакцинация</h4>
                <div class="tag-list">${imm.map(i => `<span class="tag">${esc(i)}</span>`).join('')}</div></div>` : ''}
            ${d.aiNotes ? `<div class="dc-section"><div class="ai-note">
                <div class="ai-head">⬡ Заключение ИИ-ассистента</div><p>${esc(d.aiNotes)}</p></div></div>` : ''}
            ${prescriptionsSection(d.prescriptions, objectUid)}
            ${d.note ? `<div class="dc-section"><p class="muted">${esc(d.note)}</p></div>` : ''}
        </div>`;
    }

    function vitalsChart(series) {
        const W = 560, H = 200, padL = 38, padR = 14, padT = 14, padB = 26;
        const innerW = W - padL - padR, innerH = H - padT - padB;
        const keys = ['systolic', 'diastolic', 'pulse'];
        const all = [];
        series.forEach(d => keys.forEach(k => { if (typeof d[k] === 'number') all.push(d[k]); }));
        if (all.length === 0) return '';
        let min = Math.min(...all) - 8;
        let max = Math.max(...all) + 8;
        if (max === min) max = min + 1;
        const n = series.length;
        const xFor = i => padL + (n <= 1 ? innerW / 2 : innerW * i / (n - 1));
        const yFor = v => padT + innerH * (1 - (v - min) / (max - min));

        // Colours come from CSS classes bound to theme tokens (audit FB-8): the old hardcoded
        // white grid/labels vanished on the light theme. Dark theme keeps the exact same look.
        let grid = '';
        for (let g = 0; g <= 3; g++) {
            const yy = padT + innerH * g / 3;
            const val = Math.round(max - (max - min) * g / 3);
            grid += `<line class="vc-grid" x1="${padL}" y1="${yy}" x2="${W - padR}" y2="${yy}" stroke-width="1"/>`;
            grid += `<text class="vc-label" x="${padL - 6}" y="${yy + 3}" text-anchor="end" font-size="9">${val}</text>`;
        }
        const lines = keys.map(k => {
            const pts = series.map((d, i) => `${xFor(i).toFixed(1)},${yFor(d[k]).toFixed(1)}`).join(' ');
            const dots = series.map((d, i) => `<circle class="vc-dot vc-k-${k}" cx="${xFor(i).toFixed(1)}" cy="${yFor(d[k]).toFixed(1)}" r="2.6"/>`).join('');
            return `<polyline class="vc-line vc-k-${k}" points="${pts}" fill="none" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>${dots}`;
        }).join('');
        const labels = series.map((d, i) => `<text class="vc-label" x="${xFor(i).toFixed(1)}" y="${H - 8}" text-anchor="middle" font-size="9.5">${esc(d.label)}</text>`).join('');
        return `<svg viewBox="0 0 ${W} ${H}" width="100%" preserveAspectRatio="xMidYMid meet" role="img" aria-label="График показателей">${grid}${lines}${labels}</svg>`;
    }

    function retailCard(d, objectUid) {
        const sizes = Array.isArray(d.sizes) ? d.sizes : [];
        const colors = Array.isArray(d.colors) ? d.colors : [];
        const alts = Array.isArray(d.alternatives) ? d.alternatives : [];
        const loyalty = d.loyalty || null;

        const rating = d.rating != null ? `
            <div class="rating-row"><span class="stars">${stars(d.rating)}</span>
            <span>${esc(d.rating)} ${d.reviews != null ? '· ' + esc(d.reviews) + ' отзывов' : ''}</span></div>` : '';
        // Guest projection (Scenario #1) strips price/reviews server-side — guard the
        // price block so a hidden price never renders as "0 ₸".
        const priceBlock = d.price != null
            ? `<div class="price-block"><span class="price-now">${esc(fmtPrice(d.price, d.currency))}</span></div>` : '';
        const headerMeta = (priceBlock || rating)
            ? `<div class="dc-section">${priceBlock}${rating}</div>` : '';
        const sizeRows = sizes.map(s => {
            const stock = Number(s.stock || 0);
            let cls = 'in', label = 'В наличии (' + stock + ')';
            if (stock <= 0) { cls = 'out'; label = 'Нет в наличии'; }
            else if (stock <= 5) { cls = 'low'; label = 'Мало (' + stock + ')'; }
            return `<tr><td><strong>${esc(s.size)}</strong></td><td style="text-align:right"><span class="stock ${cls}">${esc(label)}</span></td></tr>`;
        }).join('');
        const altRows = alts.map(a => {
            const link = safeUrl(a.url); // audit L-1: only http(s) — never javascript:/data:
            return `
            <div class="alt-row">
                <div><div class="alt-store">${esc(a.store)}</div><div class="alt-note">${esc(a.note || '')}</div></div>
                <div class="alt-right"><div class="alt-price">${esc(fmtPrice(a.price, d.currency))}</div>
                ${link ? `<a class="alt-link" href="${esc(link)}" target="_blank" rel="noopener">Открыть →</a>` : ''}</div>
            </div>`;
        }).join('');
        const promo = loyalty ? `
            <div class="dc-section"><h4>Программа лояльности</h4>
                <div class="promo">
                    <div><div class="promo-code">${esc(loyalty.code)}</div><div class="promo-note">${esc(loyalty.note || '')}</div></div>
                    ${loyalty.discount ? `<div class="promo-disc">${esc(loyalty.discount)}</div>` : ''}
                </div></div>` : '';

        return `
        <div class="card data-card fade-in">
            ${cardHead(d.productName || 'Товар', (d.brand ? d.brand + ' · ' : '') + 'Артикул: ' + (d.sku || objectUid), 'RETAIL')}
            ${headerMeta}
            ${d.description ? `<div class="dc-section"><p>${esc(d.description)}</p></div>` : ''}
            ${sizes.length ? `<div class="dc-section"><h4>Размеры и наличие</h4>
                <table class="tbl"><thead><tr><th>Размер</th><th style="text-align:right">Наличие</th></tr></thead><tbody>${sizeRows}</tbody></table></div>` : ''}
            ${colors.length ? `<div class="dc-section"><h4>Цвета</h4>
                <div class="tag-list">${colors.map(c => `<span class="tag">${esc(c)}</span>`).join('')}</div></div>` : ''}
            ${alts.length ? `<div class="dc-section"><h4>Где купить дешевле</h4>${altRows}</div>` : ''}
            ${promo}
        </div>`;
    }

    function ecoCard(d, objectUid) {
        const fill = Number(d.fillLevel != null ? d.fillLevel : 0);
        let gaugeColor = '#45D49A';
        if (fill >= 80) gaugeColor = '#F2706B';
        else if (fill >= 50) gaugeColor = '#E7B454';
        const waste = Array.isArray(d.wasteTypes) ? d.wasteTypes : [];
        const sched = Array.isArray(d.pickupSchedule) ? d.pickupSchedule : [];
        const schedHtml = sched.map(s => `<div class="sched-item"><span class="sd-day">${esc(s.day)}</span><span class="sd-time">${esc(s.time)}</span></div>`).join('');

        return `
        <div class="card data-card fade-in">
            ${cardHead(d.title || 'Контейнер', 'ID: ' + (d.binId || objectUid), 'ECO')}
            <div class="dc-section">
                <div class="gauge">
                    <div class="gauge-ring" style="--pct:${fill};--gauge-color:${gaugeColor}"><span class="gauge-val">${fill}%</span></div>
                    <div class="gauge-meta"><div class="gm-status">${esc(d.status || '—')}</div><div class="gm-tier">${esc(d.environmentalTier || '')}</div></div>
                </div>
            </div>
            <div class="dc-section">
                <div class="kv-grid">
                    ${kv('Адрес', d.location || '—')}
                    ${kv('Оператор', d.operator || '—')}
                    ${kv('Последний вывоз', d.lastEmptied || '—', true)}
                    ${d.coordinates ? kv('Координаты', d.coordinates, true) : ''}
                    ${d.co2SavedKg != null ? kv('CO₂ сэкономлено', d.co2SavedKg + ' кг') : ''}
                    ${d.recyclingRate != null ? kv('Переработка', d.recyclingRate + ' %') : ''}
                </div>
            </div>
            ${waste.length ? `<div class="dc-section"><h4>Типы отходов</h4>
                <div class="tag-list">${waste.map(w => `<span class="tag">${esc(w)}</span>`).join('')}</div></div>` : ''}
            ${sched.length ? `<div class="dc-section"><h4>График вывоза</h4><div class="sched-list">${schedHtml}</div></div>` : ''}
            ${reportBlock(objectUid, 'Сообщить о переполнении / поломке')}
        </div>`;
    }

    function infraCard(d, objectUid) {
        return `
        <div class="card data-card fade-in">
            ${cardHead(d.title || 'Объект', 'ID: ' + (d.assetId || objectUid), 'INFRASTRUCTURE')}
            <div class="dc-section">
                <div class="kv-grid">
                    ${kv('Тип объекта', d.assetType || '—')}
                    ${kv('Статус', d.status || '—')}
                    ${d.voltage ? kv('Напряжение', d.voltage) : ''}
                    ${kv('Адрес', d.location || '—')}
                    ${kv('Оператор', d.operator || '—')}
                    ${d.lastInspection ? kv('Последняя проверка', d.lastInspection, true) : ''}
                    ${d.nextMaintenance ? kv('Следующее ТО', d.nextMaintenance, true) : ''}
                </div>
            </div>
            ${d.technicalNotes ? `<div class="dc-section"><div class="ai-note">
                <div class="ai-head">⬡ Технические примечания</div><p>${esc(d.technicalNotes)}</p></div></div>` : ''}
            ${reportBlock(objectUid, 'Сообщить о проблеме')}
        </div>`;
    }

    function generalCard(d, objectUid) {
        const details = (d.details && typeof d.details === 'object' && !Array.isArray(d.details)) ? d.details : null;
        const kvs = details ? Object.keys(details).map(k => kv(k, details[k])).join('') : '';
        const sub = d.kind ? d.kind : 'ID: ' + objectUid;
        return `
        <div class="card data-card fade-in">
            ${cardHead(d.title || d.displayName || d.name || 'Объект', sub, 'GENERAL')}
            ${d.description ? `<div class="dc-section"><p>${esc(d.description)}</p></div>` : ''}
            ${kvs ? `<div class="dc-section"><h4>Характеристики</h4><div class="kv-grid">${kvs}</div></div>` : ''}
            ${d.note ? `<div class="dc-section"><p class="muted">${esc(d.note)}</p></div>` : ''}
        </div>`;
    }

    // -------------------------------------------------------------
    //  Report flow (eco / infrastructure)
    // -------------------------------------------------------------
    function reportBlock(objectUid, buttonLabel) {
        return `
        <div class="dc-section" data-report-for="${esc(objectUid)}">
            <button class="btn btn-gold report-toggle" type="button">⚠ ${esc(buttonLabel)}</button>
            <div class="report-form hidden mt-md">
                <div class="field">
                    <label>Опишите проблему</label>
                    <textarea class="report-msg" placeholder="Например: контейнер переполнен, крышка не закрывается"></textarea>
                </div>
                <button class="btn btn-primary mt-sm report-submit" type="button">Отправить обращение</button>
            </div>
        </div>`;
    }

    function wireReportButtons() {
        document.querySelectorAll('[data-report-for]').forEach(section => {
            const objectUid = section.getAttribute('data-report-for');
            const toggle = section.querySelector('.report-toggle');
            const form = section.querySelector('.report-form');
            const submit = section.querySelector('.report-submit');
            const msg = section.querySelector('.report-msg');
            if (!toggle || !form || !submit) return;
            toggle.addEventListener('click', () => form.classList.toggle('hidden'));
            submit.addEventListener('click', async () => {
                submit.disabled = true; submit.textContent = 'Отправка…';
                try {
                    const { ok, data: res } = await apiJson('/api/v2/report', {
                        method: 'POST', body: { objectUid, message: (msg && msg.value.trim()) || '' }
                    });
                    if (!ok || !res) throw new Error((res && res.message) || 'Не удалось отправить обращение');
                    toast('Обращение зарегистрировано.', 'ok');
                    const slot = document.getElementById('card-slot');
                    slot.innerHTML = `${verdictHtml('APPROVED', res.reason, res.riskLevel, 'Обращение принято')}<div class="pipeline" id="report-pipeline"></div>`;
                    animatePipeline(document.getElementById('report-pipeline'), res, 'APPROVED');
                } catch (err) {
                    toast(err.message, 'err');
                    submit.disabled = false; submit.textContent = 'Отправить обращение';
                }
            });
        });
    }

    // -------------------------------------------------------------
    //  Camera scanner (html5-qrcode)
    // -------------------------------------------------------------
    function openScanner() {
        const overlay = document.getElementById('scanner-overlay');
        overlay.hidden = false;
        if (typeof Html5Qrcode === 'undefined') {
            toast('Сканер недоступен. Обновите страницу и попробуйте снова.', 'err');
            overlay.hidden = true;
            return;
        }
        document.getElementById('reader').innerHTML = '';
        html5QrInstance = new Html5Qrcode('reader');
        html5QrInstance.start(
            { facingMode: 'environment' },
            { fps: 10, qrbox: { width: 240, height: 240 } },
            (decodedText) => {
                closeScanner();
                const id = normalizeScanInput(decodedText);
                const input = document.getElementById('manual-uid');
                if (input) input.value = id;
                doScan(id);
            },
            () => { /* per-frame miss: ignore */ }
        ).catch(err => {
            toast('Не удалось открыть камеру: ' + err, 'err');
            closeScanner();
        });
    }

    function closeScanner() {
        const overlay = document.getElementById('scanner-overlay');
        if (html5QrInstance) {
            html5QrInstance.stop().then(() => html5QrInstance.clear()).catch(() => {}).finally(() => { html5QrInstance = null; });
        }
        if (overlay) overlay.hidden = true;
    }

    // -------------------------------------------------------------
    //  Init
    // -------------------------------------------------------------
    // Run a deep-linked scan once we know who the visitor is. If they are not signed in the
    // intent stays on the auth screen (a banner) and runs right after they enter (Point 1).
    function consumePendingScan() {
        if (!pendingScanTarget || !currentUser) return;
        const target = pendingScanTarget;
        pendingScanTarget = null;
        if (!currentUser.admin) citizenTab = 'terminal';
        doScan(target);
    }

    // -------------------------------------------------------------
    //  Demo "Time Machine" (Point 5): mock the session's hour so the
    //  working-hours gate can be shown live (e.g. medical access 08–18).
    // -------------------------------------------------------------
    function renderTimeMachine() {
        let el = document.getElementById('time-machine');
        // Admin-only tool — the time machine drives /api/v2/dev/time, which is ROLE_ADMIN
        // (SecurityConfig + @PreAuthorize). Gating on demoToolsEnabled() would render it for every
        // user under DEMO_MODE and 403 on use, so it stays strictly admin-only regardless of DEMO_MODE.
        if (!currentUser || !currentUser.admin) { if (el) el.remove(); return; }
        if (!el) {
            el = document.createElement('div');
            el.id = 'time-machine';
            el.className = 'time-machine';
            document.body.appendChild(el);
        }
        refreshTimeMachine(el);
    }

    async function refreshTimeMachine(el) {
        el = el || document.getElementById('time-machine');
        if (!el) return;
        let state = { effectiveHour: new Date().getHours(), mockHour: null, workingHours: null };
        try {
            const { ok, data } = await apiJson('/api/v2/dev/time');
            if (ok && data) state = data;
        } catch (_) { /* local fallback */ }
        const hh = String(state.effectiveHour).padStart(2, '0') + ':00';
        const mocked = state.mockHour != null;
        const work = state.workingHours;
        el.innerHTML = `
            <button class="tm-toggle" id="tm-toggle" type="button" title="Машина времени — только для демо">
                <span class="tm-demo">DEMO</span>
                <span class="tm-ico">🕓</span>
                <span class="tm-now ${work === false ? 'off' : work === true ? 'on' : ''}">${hh}${mocked ? ' ⚙' : ''}</span>
            </button>
            <div class="tm-panel" id="tm-panel" hidden>
                <div class="tm-head">Машина времени · <span class="demo-tag">DEMO ONLY</span></div>
                <div class="tm-sub">Демо-час платформы: <strong>${hh}</strong> ${mocked ? '(имитация)' : '(сервер)'}<br>
                    Рабочее окно 08:00–18:00 ${work === false ? '· сейчас ВНЕ окна' : work === true ? '· сейчас в окне' : ''}</div>
                <div class="tm-row">
                    <button class="btn btn-sm btn-ghost tm-set" data-h="10" type="button">10:00 рабочее</button>
                    <button class="btn btn-sm btn-ghost tm-set" data-h="22" type="button">22:00 нерабочее</button>
                </div>
                <div class="tm-row">
                    <input id="tm-hour" type="number" min="0" max="23" placeholder="Час 0–23">
                    <button class="btn btn-sm btn-primary" id="tm-apply" type="button">Задать</button>
                </div>
                <button class="btn btn-sm btn-ghost btn-block" id="tm-reset" type="button">Сбросить на серверное время</button>
            </div>`;
        const panel = el.querySelector('#tm-panel');
        el.querySelector('#tm-toggle').addEventListener('click', () => { panel.hidden = !panel.hidden; });
        el.querySelectorAll('.tm-set').forEach(b => b.addEventListener('click', () => setMockHour(Number(b.dataset.h))));
        el.querySelector('#tm-apply').addEventListener('click', () => {
            const v = el.querySelector('#tm-hour').value;
            if (v !== '') setMockHour(Number(v));
        });
        el.querySelector('#tm-reset').addEventListener('click', () => setMockHour(null));
    }

    async function setMockHour(hour) {
        try {
            const { ok } = await apiJson('/api/v2/dev/time',
                { method: 'POST', body: hour == null ? {} : { hour } });
            if (ok) toast(hour == null ? 'Время сброшено на серверное.'
                : `Час сессии: ${String(hour).padStart(2, '0')}:00`, 'ok');
        } catch (_) { toast('Не удалось изменить время.', 'err'); }
        refreshTimeMachine();
    }

    async function init() {
        const closeBtn = document.getElementById('scanner-close');
        if (closeBtn) closeBtn.addEventListener('click', closeScanner);

        const themeBtn = document.getElementById('themeToggle');
        if (themeBtn) themeBtn.addEventListener('click', toggleTheme);
        syncThemeIcon();

        // Native-scan deep link: a phone's stock camera opened our QR URL → /s/<identifier>.
        const deep = location.pathname.match(/^\/s\/(.+)$/);
        if (deep) {
            try { pendingScanTarget = decodeURIComponent(deep[1]); } catch (_) { pendingScanTarget = deep[1]; }
            history.replaceState(null, '', '/');  // clean the URL so a refresh doesn't re-trigger
        }

        checkHealth();
        setInterval(checkHealth, 30000);

        // Coming back to the tab refreshes incoming approvals at once (the poll skips hidden tabs).
        document.addEventListener('visibilitychange', () => {
            if (!document.hidden && currentUser && !currentUser.admin) loadAccessRequests();
        });

        try { await loadMe(); } catch (_) { currentUser = null; }
        route();
        enforcePasswordChange();
        consumePendingScan();
        renderTimeMachine();
    }

    document.addEventListener('DOMContentLoaded', init);
})();
