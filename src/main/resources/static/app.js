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
    let citizenTab = 'terminal';
    let sessionInfo = null;
    let notifList = [];
    let complaintPrefill = null;   // interactionUid pre-selected when filing from history
    let pendingScanTarget = null;  // identifier from a /s/<id> deep link or a guest conversion
    let profilePollTimer = null;   // polls for the owner's confirmation after a profile scan
    let citizenTabsForGuest = ['terminal', 'myqr']; // guests never see history / complaints

    // Demo-only tooling (the "time machine" + manual UID entry) is hidden from regular users
    // so the product reads as a real scan-first app. Admins always see it; flip DEMO_MODE to
    // true to expose it to everyone for a live presentation.
    const DEMO_MODE = false;
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
            INFRASTRUCTURE: 'Инфраструктурный объект', GENERAL: 'Общий объект', UNKNOWN: 'Неизвестно'
        };
        return labels[category] || category;
    }

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
        const bar = document.getElementById('appbar');
        if (bar) bar.hidden = true;
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
        if (!currentUser) {
            document.getElementById('appbar').hidden = true;
            renderAuth();
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
    //  AUTH VIEW
    // =============================================================
    function renderAuth() {
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
                    <button class="tab" id="tab-register" type="button">Регистрация</button>
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

                <form id="register-form" class="form-grid hidden">
                    <div class="form-row">
                        <div class="field">
                            <label for="re-firstName">Имя</label>
                            <input id="re-firstName" type="text" placeholder="Имя">
                        </div>
                        <div class="field">
                            <label for="re-lastName">Фамилия</label>
                            <input id="re-lastName" type="text" placeholder="Фамилия">
                        </div>
                    </div>
                    <div class="field">
                        <label for="re-username">Имя пользователя</label>
                        <input id="re-username" type="text" autocomplete="username" placeholder="Латиница, от 3 символов">
                    </div>
                    <div class="field">
                        <label for="re-password">Пароль</label>
                        <input id="re-password" type="password" autocomplete="new-password" placeholder="Не менее 12 символов, буквы и цифры">
                    </div>
                    <div class="form-row">
                        <div class="field">
                            <label for="re-employment">Статус занятости</label>
                            <select id="re-employment">
                                <option value="EMPLOYED">Трудоустроен(а)</option>
                                <option value="UNEMPLOYED">Не трудоустроен(а)</option>
                            </select>
                        </div>
                        <div class="field">
                            <label>Роль при регистрации</label>
                            <div class="field-static">Гражданин</div>
                            <div class="field-hint">
                                Самостоятельная регистрация всегда создаёт роль «Гражданин».
                                Специализированные роли (врач, инспектор, администратор)
                                назначает администратор после проверки.
                            </div>
                        </div>
                    </div>
                    <div class="field-error" id="register-error"></div>
                    <button class="btn btn-primary btn-block" id="register-submit" type="submit">Создать аккаунт</button>
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

        const guestBtn = document.getElementById('guest-btn');
        if (guestBtn) guestBtn.addEventListener('click', doGuest);

        // Demo logins (clickable) — convenience for the live demo. Clicking a chip fills
        // the login form with that account's credentials and switches to the Вход tab.
        const demoAccounts = [
            { role: 'Администратор', user: 'admin', pass: 'Admin123!', badge: 'admin' },
            { role: 'Продавец', user: 'seller', pass: 'Seller123!', badge: 'user' },
            { role: 'Врач', user: 'doctor', pass: 'Doctor123!', badge: 'user' },
            { role: 'Фармацевт', user: 'pharmacist', pass: 'Pharma123!', badge: 'user' },
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

        // Self-service registration always provisions a CITIZEN (audit 4.1/4.2), so the
        // employment selector no longer gates a profession picker — there isn't one.
        // Demo account passwords are intentionally NOT printed on the login screen
        // (audit 1.3); they live in the README for evaluators only.

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

        registerForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            const errEl = document.getElementById('register-error');
            errEl.textContent = '';
            const payload = {
                firstName: document.getElementById('re-firstName').value.trim(),
                lastName: document.getElementById('re-lastName').value.trim(),
                username: document.getElementById('re-username').value.trim(),
                password: document.getElementById('re-password').value,
                employmentStatus: document.getElementById('re-employment').value,
                // Self-registration is always CITIZEN; the server enforces this regardless.
                profession: 'CITIZEN'
            };
            if (!payload.firstName || !payload.lastName || !payload.username || !payload.password) {
                errEl.textContent = 'Заполните все поля.'; return;
            }
            const btn = document.getElementById('register-submit');
            btn.disabled = true; btn.textContent = 'Создаём…';
            try {
                const { ok, status, data } = await apiJson('/api/auth/register', { method: 'POST', body: payload });
                if (!ok) {
                    let msg = (data && data.message) || 'Не удалось зарегистрироваться';
                    if (status === 409) msg = (data && data.message) || 'Имя пользователя уже занято';
                    if (data && data.details) { const f = Object.values(data.details)[0]; if (f) msg = f; }
                    throw new Error(msg);
                }
                toast('Аккаунт создан. Выполняем вход…', 'ok');
                await doLogin(payload.username, payload.password);
                await maybeMergeGuest();
                route();
                consumePendingScan();
            } catch (err) {
                errEl.textContent = err.message;
                btn.disabled = false; btn.textContent = 'Создать аккаунт';
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
            case 'sos': renderAdminSos(); break;
            case 'stats': renderAdminStats(); break;
            case 'complaints': renderAdminComplaints(); break;
            case 'audit': renderAdminAudit(); break;
            default: renderAdminManage();
        }
        refreshSosBadge();
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
                    <textarea id="cmp-response" placeholder="Опишите принятое решение — пользователь получит уведомление"></textarea></div>`,
            dismissValue: null,
            onBody: (card, { close }) => {
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
    function renderCitizen() {
        const guest = !!currentUser.guest;
        // Guests get a deliberately reduced surface: no personal history / complaints tabs —
        // their actions are tracked silently and only surface after they register (Point 6).
        const tabs = guest
            ? [['terminal', 'Терминал'], ['myqr', 'Мой QR']]
            : [['terminal', 'Терминал'], ['myqr', 'Мой QR'], ['objects', 'Мои объекты'],
               ['history', 'Моя история'], ['complaints', 'Жалобы']];
        if (!tabs.some(t => t[0] === citizenTab)) citizenTab = 'terminal';
        app().innerHTML = `
        <div class="fade-in">
            <div class="page-head">
                <div>
                    <h2>Терминал доступа</h2>
                    <p>Отсканируйте QR-код объекта камерой. Шлюз проверит ваши права и контекст,
                    затем покажет доступные данные из реестра.</p>
                </div>
            </div>

            <div class="identity-strip">
                <div class="id-pill"><span class="idp-k">Личность</span>
                    <span class="idp-v primary">${esc(shortId(currentUser.identityUid))}</span></div>
                <div class="id-pill"><span class="idp-k">Основной QR</span>
                    <span class="idp-v gold">${esc(shortId(currentUser.primaryQrUid))}</span></div>
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
            b.classList.toggle('active', b.dataset.tab === citizenTab);
            b.addEventListener('click', () => { citizenTab = b.dataset.tab; renderCitizen(); });
        });

        wireContextBar();
        loadCitizenContext();
        loadAccessRequests();

        if (citizenTab === 'terminal') renderCitizenTerminal();
        else if (citizenTab === 'myqr') renderCitizenMyQr();
        else if (citizenTab === 'objects') renderCitizenObjects();
        else if (citizenTab === 'history') renderCitizenHistory();
        else if (citizenTab === 'complaints') renderCitizenComplaints();
        else renderCitizenTerminal();
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
                toast('SOS-запрос зарегистрирован и эскалирован.', 'ok');
                const slot = openResultPage(
                    `${verdictHtml('APPROVED', data.reason, data.riskLevel)}<div class="pipeline" id="sos-pipeline"></div>`);
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
            { name: 'Цифровая визитка', code: 'IDENTITY:aaaaaaaa-0000-0000-0000-000000000007', tag: 'личность · Owner Approval + Trust Level' }
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
        const cleaned = String(objectUid).trim();
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
    function openResultPage(innerHtml) {
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
                    <span class="result-bar-title">Результат сканирования</span>
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
        if (approved && data.data) cardHtml = renderCardByCategory(data.category, data.data, data.objectUid);
        const tierHtml = (approved && data.accessTier) ? accessTierHtml(data.accessTier) : '';
        const ctaHtml = data.registrationRequired ? guestCtaHtml(data.cta) : '';
        result.innerHTML = `
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
                        <option value="EMPLOYED">Трудоустроен(а)</option>
                        <option value="UNEMPLOYED" selected>Не трудоустроен(а)</option>
                    </select></div>
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
        SCAN: 'Скан объекта', PROFILE_SCAN: 'Скан профиля',
        REPORT: 'Обращение', SOS: 'SOS', QR_CREATION: 'Создание QR'
    };
    const ISTATUS_RU = { PENDING: 'Ожидает', CONFIRMED: 'Подтверждено', REJECTED: 'Отклонено' };
    const COMPLAINT_RU = { NEW: 'Новая', IN_PROGRESS: 'В работе', RESOLVED: 'Решена', REJECTED: 'Отклонена' };

    async function loadAccessRequests() {
        const box = document.getElementById('access-requests');
        if (!box) return;
        try {
            const { ok, data } = await apiJson('/api/v2/access/pending');
            if (!ok || !Array.isArray(data) || data.length === 0) { box.innerHTML = ''; return; }
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
        } catch (_) { box.innerHTML = ''; }
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
                    <p class="muted" style="margin-top:10px">QR — только идентификатор. Он не несёт ролей, прав или доверия.</p>
                </div>
            </section>`;
            document.getElementById('qr-demo-btn').addEventListener('click', () => openQrDemo(data.fullName, data.qrImageDataUri));
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
                            <input id="cmp-subject" type="text" placeholder="Кратко о проблеме">
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
                            <textarea id="cmp-desc" placeholder="Подробности"></textarea>
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
    function verdictHtml(outcome, reason, riskLevel) {
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
                    <div class="v-title">${v.title}</div>
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
            default: return generalCard(d, objectUid);
        }
    }

    function cardHead(title, sub, category) {
        const labels = { MEDICAL: 'Медицина', RETAIL: 'Товар', ECO: 'Экология', INFRASTRUCTURE: 'Инфраструктура', GENERAL: 'Объект' };
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
                    <span class="legend-item"><span class="legend-swatch" style="background:#3CC9E8"></span>Систолическое</span>
                    <span class="legend-item"><span class="legend-swatch" style="background:#F3CC73"></span>Диастолическое</span>
                    <span class="legend-item"><span class="legend-swatch" style="background:#F2706B"></span>Пульс</span>
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
        const colors = { systolic: '#3CC9E8', diastolic: '#F3CC73', pulse: '#F2706B' };
        const all = [];
        series.forEach(d => keys.forEach(k => { if (typeof d[k] === 'number') all.push(d[k]); }));
        if (all.length === 0) return '';
        let min = Math.min(...all) - 8;
        let max = Math.max(...all) + 8;
        if (max === min) max = min + 1;
        const n = series.length;
        const xFor = i => padL + (n <= 1 ? innerW / 2 : innerW * i / (n - 1));
        const yFor = v => padT + innerH * (1 - (v - min) / (max - min));

        let grid = '';
        for (let g = 0; g <= 3; g++) {
            const yy = padT + innerH * g / 3;
            const val = Math.round(max - (max - min) * g / 3);
            grid += `<line x1="${padL}" y1="${yy}" x2="${W - padR}" y2="${yy}" stroke="rgba(255,255,255,0.10)" stroke-width="1"/>`;
            grid += `<text x="${padL - 6}" y="${yy + 3}" text-anchor="end" font-size="9" fill="rgba(226,238,247,0.42)">${val}</text>`;
        }
        const lines = keys.map(k => {
            const pts = series.map((d, i) => `${xFor(i).toFixed(1)},${yFor(d[k]).toFixed(1)}`).join(' ');
            const dots = series.map((d, i) => `<circle cx="${xFor(i).toFixed(1)}" cy="${yFor(d[k]).toFixed(1)}" r="2.6" fill="${colors[k]}"/>`).join('');
            return `<polyline points="${pts}" fill="none" stroke="${colors[k]}" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>${dots}`;
        }).join('');
        const labels = series.map((d, i) => `<text x="${xFor(i).toFixed(1)}" y="${H - 8}" text-anchor="middle" font-size="9.5" fill="rgba(226,238,247,0.42)">${esc(d.label)}</text>`).join('');
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
                    slot.innerHTML = `${verdictHtml('APPROVED', res.reason, res.riskLevel)}<div class="pipeline" id="report-pipeline"></div>`;
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
                const input = document.getElementById('manual-uid');
                if (input) input.value = decodedText;
                doScan(decodedText);
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
        // Demo-only tool: shown to admins or when DEMO_MODE is on; removed for regular users.
        if (!currentUser || !demoToolsEnabled()) { if (el) el.remove(); return; }
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
                <div class="tm-sub">Час сессии: <strong>${hh}</strong> ${mocked ? '(имитация)' : '(сервер)'}<br>
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

        try { await loadMe(); } catch (_) { currentUser = null; }
        route();
        enforcePasswordChange();
        consumePendingScan();
        renderTimeMachine();
    }

    document.addEventListener('DOMContentLoaded', init);
})();
