/* ============================================================
   IDEAQR Digital Gateway — Stage 3 SPA
   Vanilla JS. JSON API + Spring Security session.
   All user-facing text is Russian. Official state-portal UI.
   ============================================================ */

(() => {
    'use strict';

    // -------------------------------------------------------------
    //  State
    // -------------------------------------------------------------
    let currentUser = null;
    let html5QrInstance = null;
    let adminTab = 'manage';
    let citizenTab = 'terminal';
    let sessionInfo = null;
    let notifList = [];

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
            MEDICAL: 'Медицинская карта', RETAIL: 'Розничный товар', ECO: 'Экологический объект',
            INFRASTRUCTURE: 'Инфраструктурный объект', GENERAL: 'Общий объект', UNKNOWN: 'Неизвестно'
        };
        return labels[category] || category;
    }

    // -------------------------------------------------------------
    //  API helpers
    // -------------------------------------------------------------
    async function apiJson(path, { method = 'GET', body } = {}) {
        const opts = { method, credentials: 'same-origin', headers: {} };
        if (body !== undefined) {
            opts.headers['Content-Type'] = 'application/json';
            opts.body = JSON.stringify(body);
        }
        const res = await fetch(path, opts);
        const text = await res.text();
        let data = null;
        if (text) { try { data = JSON.parse(text); } catch (_) { data = { raw: text }; } }
        return { ok: res.ok, status: res.status, data };
    }

    async function apiForm(path, params) {
        const res = await fetch(path, {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: new URLSearchParams(params).toString()
        });
        const text = await res.text();
        let data = null;
        if (text) { try { data = JSON.parse(text); } catch (_) { data = { raw: text }; } }
        return { ok: res.ok, status: res.status, data };
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
            try { localStorage.setItem('ideaqr_guest_uid', data.identityUid); } catch (_) { /* ignore */ }
            toast('Вы вошли как гость. Действия будут записаны.', 'info');
            route();
        } catch (err) {
            toast(err.message, 'err');
        }
    }

    // After a guest registers, transfer the guest history into the new identity.
    async function maybeMergeGuest() {
        let guestUid = null;
        try { guestUid = localStorage.getItem('ideaqr_guest_uid'); } catch (_) { /* ignore */ }
        if (!guestUid || !currentUser || guestUid === currentUser.identityUid) return;
        try {
            const { ok, data } = await apiJson('/api/v2/guest/merge',
                { method: 'POST', body: { guestIdentityUid: guestUid } });
            if (ok) toast((data && data.message) || 'История гостя перенесена.', 'ok');
        } catch (_) { /* ignore */ }
        try { localStorage.removeItem('ideaqr_guest_uid'); } catch (_) { /* ignore */ }
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
    //  Routing
    // -------------------------------------------------------------
    function route() {
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
            <button class="btn btn-danger btn-sm" id="logout-btn" type="button">Выйти</button>`;
        document.getElementById('logout-btn').addEventListener('click', doLogout);
    }

    // =============================================================
    //  AUTH VIEW
    // =============================================================
    function renderAuth() {
        app().innerHTML = `
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
                        <div class="dc-label">Демонстрационные аккаунты</div>
                        <div class="cred-grid" id="cred-grid"></div>
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
                        <input id="re-password" type="password" autocomplete="new-password" placeholder="Не менее 6 символов">
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
                            <label for="re-profession">Профессия</label>
                            <select id="re-profession">
                                <option value="CITIZEN">Гражданин</option>
                                <option value="DOCTOR">Врач</option>
                                <option value="RETAIL_ADMIN">Администратор торговли</option>
                                <option value="INSPECTOR">Инспектор инфраструктуры</option>
                            </select>
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

        const creds = [
            { role: 'Администратор торговли', login: 'admin', pass: 'Admin123!', badge: 'admin' },
            { role: 'Врач', login: 'doctor', pass: 'Doctor123!', badge: 'user' },
            { role: 'Инспектор', login: 'inspector', pass: 'Inspect123!', badge: 'user' },
            { role: 'Гражданин', login: 'citizen', pass: 'Citizen123!', badge: 'user' }
        ];
        const grid = document.getElementById('cred-grid');
        grid.innerHTML = creds.map((c, i) => `
            <button class="cred-chip" type="button" data-i="${i}">
                <div class="cc-role">${esc(c.role)}</div>
                <div class="cc-login">${esc(c.login)} / ${esc(c.pass)}</div>
                <span class="cc-badge ${c.badge}">${c.badge === 'admin' ? 'Админ' : 'Пользователь'}</span>
            </button>`).join('');
        grid.querySelectorAll('.cred-chip').forEach(btn => {
            btn.addEventListener('click', () => {
                const c = creds[Number(btn.dataset.i)];
                activate('login');
                document.getElementById('li-username').value = c.login;
                document.getElementById('li-password').value = c.pass;
            });
        });

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
                profession: document.getElementById('re-profession').value
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
                <button data-tab="audit" type="button">Журнал системы</button>
            </div>
            <div id="admin-body"></div>
        </div>`;

        const nav = app().querySelector('.view-nav');
        nav.querySelectorAll('button').forEach(b => {
            b.classList.toggle('active', b.dataset.tab === adminTab);
            b.addEventListener('click', () => { adminTab = b.dataset.tab; renderAdmin(); });
        });

        if (adminTab === 'manage') renderAdminManage();
        else renderAdminAudit();
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
                            <option value="RETAIL">Розничный товар</option>
                            <option value="ECO">Экологический объект</option>
                            <option value="INFRASTRUCTURE">Инфраструктурный объект</option>
                            <option value="MEDICAL">Медицинская карта</option>
                            <option value="GENERAL">Общий объект</option>
                        </select>
                    </div>
                    <div class="field">
                        <label for="cf-name">Наименование</label>
                        <input id="cf-name" type="text" placeholder="например, Adidas — чёрная футболка">
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

    async function loadAdminObjects() {
        const list = document.getElementById('obj-list');
        try {
            const { ok, data } = await apiJson('/api/admin/qr/list');
            if (!ok || !Array.isArray(data)) { list.innerHTML = `<div class="obj-empty">Не удалось загрузить список.</div>`; return; }
            if (data.length === 0) { list.innerHTML = `<div class="obj-empty">Пока нет созданных объектов.</div>`; return; }
            list.innerHTML = data.map(o => `
                <div class="obj-item">
                    <img src="${esc(o.qrImageDataUri)}" alt="QR ${esc(o.objectUid)}">
                    <div class="oi-body">
                        <div class="oi-name">${esc(o.displayName)}</div>
                        <div class="oi-uid">${esc(o.objectUid)}</div>
                        <div class="oi-meta">${esc(categoryLabel(o.category))} · ${esc(o.createdAt || '')}</div>
                    </div>
                </div>`).join('');
        } catch (_) {
            list.innerHTML = `<div class="obj-empty">Ошибка загрузки списка.</div>`;
        }
    }

    function renderAdminAudit() {
        document.getElementById('admin-body').innerHTML = `
        <section class="panel panel-pad">
            <div class="audit-head">
                <div class="section-title" style="margin-bottom:0">Неизменяемый журнал системы</div>
                <button class="btn btn-ghost btn-sm" id="audit-refresh" type="button">Обновить</button>
            </div>
            <div class="table-scroll">
                <table class="audit-tbl">
                    <thead><tr><th>Событие</th><th>Статус</th><th>Объект</th><th>ID записи</th><th>Время</th></tr></thead>
                    <tbody id="audit-body"><tr><td colspan="5" class="empty">${'Загрузка…'}</td></tr></tbody>
                </table>
            </div>
        </section>`;
        document.getElementById('audit-refresh').addEventListener('click', () => loadAudit('/api/v2/audit'));
        loadAudit('/api/v2/audit');
    }

    // =============================================================
    //  CITIZEN VIEW
    // =============================================================
    function renderCitizen() {
        app().innerHTML = `
        <div class="fade-in">
            <div class="page-head">
                <div>
                    <h2>Терминал доступа</h2>
                    <p>Отсканируйте QR-код объекта камерой или введите идентификатор вручную. Шлюз проверит
                    ваши права и контекст, затем покажет доступные данные из реестра.</p>
                </div>
            </div>

            <div class="identity-strip">
                <div class="id-pill"><span class="idp-k">Личность</span>
                    <span class="idp-v primary">${esc(shortId(currentUser.identityUid))}</span></div>
                <div class="id-pill"><span class="idp-k">Основной QR</span>
                    <span class="idp-v gold">${esc(shortId(currentUser.primaryQrUid))}</span></div>
                <div class="id-pill"><span class="idp-k">Уровень доверия</span>
                    <span class="idp-v">${esc(currentUser.trustLevel)} / 100</span></div>
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

            <div class="view-nav">
                <button data-tab="terminal" type="button">Терминал</button>
                <button data-tab="audit" type="button">Мой журнал</button>
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

        if (citizenTab === 'terminal') renderCitizenTerminal();
        else renderCitizenAudit();
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
                    const names = orgs.map((o, i) => `${i + 1}. ${o.name} (${o.role})`).join('\n');
                    const choice = window.prompt('Выберите организацию:\n' + names, '1');
                    if (choice === null) return;
                    const picked = orgs[parseInt(choice, 10) - 1];
                    if (!picked) { toast('Организация не выбрана.', 'err'); return; }
                    orgUid = picked.organizationUid;
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
        if (!window.confirm('Отправить SOS-сигнал? Будет создан приоритетный запрос.')) return;
        try {
            const { ok, data } = await apiJson('/api/v2/sos',
                { method: 'POST', body: { message: 'SOS из терминала' } });
            if (ok && data) {
                toast('SOS-запрос зарегистрирован и эскалирован.', 'ok');
                const slot = document.getElementById('scan-result');
                if (slot) {
                    slot.innerHTML = `${verdictHtml('APPROVED', data.reason, data.riskLevel)}<div class="pipeline" id="sos-pipeline"></div>`;
                    animatePipeline(document.getElementById('sos-pipeline'), data, 'APPROVED');
                }
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
        if (guestReg) guestReg.addEventListener('click', doLogout);
    }

    function renderCitizenTerminal() {
        document.getElementById('citizen-body').innerHTML = `
        <div class="split split-wide">
            <section class="panel panel-pad">
                <div class="section-title">Сканирование</div>
                <div class="scan-actions">
                    <button class="btn btn-primary" id="open-scanner" type="button">📷 Сканировать камерой</button>
                </div>
                <div class="manual-row">
                    <input id="manual-uid" type="text" placeholder="Идентификатор объекта, напр. PATIENT_7291">
                    <button class="btn btn-ghost" id="manual-go" type="button">Проверить</button>
                </div>

                <div class="quick-label">Быстрые сценарии</div>
                <div class="quick-chips" id="quick-chips"></div>

                <div class="context-row">
                    <label for="context-select">Контекст времени:</label>
                    <select id="context-select">
                        <option value="now">Текущее время</option>
                        <option value="10">Рабочее — 10:00</option>
                        <option value="23">Нерабочее — 23:00</option>
                        <option value="3">Ночь — 03:00</option>
                    </select>
                </div>
            </section>

            <section class="panel panel-pad">
                <div class="section-title">Результат</div>
                <div id="scan-result">
                    <div class="placeholder-box">
                        <div class="pb-ico">⊡</div>
                        <div class="pb-title">Ожидание сканирования</div>
                        <div class="pb-sub">Выберите сценарий или отсканируйте код</div>
                    </div>
                </div>
            </section>
        </div>`;

        const quick = [
            { name: 'Пациент 7291', code: 'PATIENT_7291', tag: 'требуется врач + рабочее время' },
            { name: 'Футболка Adidas', code: 'RETAIL_ADIDAS_SHIRT', tag: 'общедоступно' },
            { name: 'Контейнер №102', code: 'ECO_SMART_BIN_102', tag: 'общедоступно' },
            { name: 'Подстанция №07', code: 'INFRA_SUBSTATION_07', tag: 'требуется инспектор + рабочее время' }
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
        document.getElementById('open-scanner').addEventListener('click', openScanner);
    }

    function getContextHour() {
        const v = document.getElementById('context-select');
        if (!v || v.value === 'now') return null;
        return parseInt(v.value, 10);
    }

    async function doScan(objectUid) {
        const cleaned = String(objectUid).trim();
        if (cleaned.toUpperCase().startsWith('IDENTITY:')) {
            toast('Это персональный QR личности, а не объект.', 'info');
            return;
        }
        const result = document.getElementById('scan-result');
        result.innerHTML = inlineLoad('Проверка прав доступа и контекста…');
        const body = { objectUid: cleaned };
        const ctx = getContextHour();
        if (ctx !== null) body.contextHour = ctx;
        try {
            const { ok, data } = await apiJson('/api/v2/scan', { method: 'POST', body });
            if (!data || (!ok && !data.outcome)) throw new Error((data && data.message) || 'Ошибка сканирования');
            renderScanResult(data);
        } catch (err) {
            result.innerHTML = `<div class="placeholder-box"><div class="pb-ico">⚠</div>
                <div class="pb-title">Ошибка</div><div class="pb-sub">${esc(err.message)}</div></div>`;
            toast(err.message, 'err');
        }
    }

    function renderScanResult(data) {
        const result = document.getElementById('scan-result');
        const approved = data.outcome === 'APPROVED';
        let cardHtml = '';
        if (approved && data.data) cardHtml = renderCardByCategory(data.category, data.data, data.objectUid);
        result.innerHTML = `
            ${verdictHtml(data.outcome, data.reason, data.riskLevel)}
            <div class="pipeline" id="scan-pipeline"></div>
            <div id="card-slot" class="mt-md">${cardHtml}</div>`;
        animatePipeline(document.getElementById('scan-pipeline'), data, data.outcome);
        wireReportButtons();
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
        </section>`;
        document.getElementById('audit-refresh').addEventListener('click', () => loadAudit('/api/v2/audit/me'));
        loadAudit('/api/v2/audit/me');
    }

    // -------------------------------------------------------------
    //  Shared: audit table loader
    // -------------------------------------------------------------
    async function loadAudit(path) {
        const body = document.getElementById('audit-body');
        if (!body) return;
        try {
            const { ok, data } = await apiJson(path);
            if (!ok || !Array.isArray(data)) {
                body.innerHTML = `<tr><td colspan="5" class="empty">Не удалось загрузить журнал.</td></tr>`; return;
            }
            if (data.length === 0) {
                body.innerHTML = `<tr><td colspan="5" class="empty">Событий пока нет.</td></tr>`; return;
            }
            body.innerHTML = data.map(h => `
                <tr>
                    <td class="evt">${esc(EVENT_RU[h.eventType] || h.eventType || 'Событие')}</td>
                    <td>${eventTag(h.eventType)}</td>
                    <td class="mono">${esc(h.objectUid || '—')}</td>
                    <td class="uuid">${esc(shortId(h.historyUid))}</td>
                    <td class="ts">${esc(h.createdAt || '—')}</td>
                </tr>`).join('');
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
        const labels = { MEDICAL: 'Медицина', RETAIL: 'Розница', ECO: 'Экология', INFRASTRUCTURE: 'Инфраструктура', GENERAL: 'Объект' };
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
                    <span class="legend-item"><span class="legend-swatch" style="background:#0C6E8F"></span>Систолическое</span>
                    <span class="legend-item"><span class="legend-swatch" style="background:#E0A82E"></span>Диастолическое</span>
                    <span class="legend-item"><span class="legend-swatch" style="background:#C23A37"></span>Пульс</span>
                </div></div></div>` : ''}
            ${visits.length ? `<div class="dc-section"><h4>История посещений</h4>
                <table class="tbl"><thead><tr><th>Дата</th><th>Клиника</th><th>Причина</th><th>Врач</th></tr></thead><tbody>${visitRows}</tbody></table></div>` : ''}
            ${imm.length ? `<div class="dc-section"><h4>Вакцинация</h4>
                <div class="tag-list">${imm.map(i => `<span class="tag">${esc(i)}</span>`).join('')}</div></div>` : ''}
            ${d.aiNotes ? `<div class="dc-section"><div class="ai-note">
                <div class="ai-head">⬡ Заключение ИИ-ассистента</div><p>${esc(d.aiNotes)}</p></div></div>` : ''}
            ${d.note ? `<div class="dc-section"><p class="muted">${esc(d.note)}</p></div>` : ''}
        </div>`;
    }

    function vitalsChart(series) {
        const W = 560, H = 200, padL = 38, padR = 14, padT = 14, padB = 26;
        const innerW = W - padL - padR, innerH = H - padT - padB;
        const keys = ['systolic', 'diastolic', 'pulse'];
        const colors = { systolic: '#0C6E8F', diastolic: '#E0A82E', pulse: '#C23A37' };
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
            grid += `<line x1="${padL}" y1="${yy}" x2="${W - padR}" y2="${yy}" stroke="#DBE5EE" stroke-width="1"/>`;
            grid += `<text x="${padL - 6}" y="${yy + 3}" text-anchor="end" font-size="9" fill="#9AAABB">${val}</text>`;
        }
        const lines = keys.map(k => {
            const pts = series.map((d, i) => `${xFor(i).toFixed(1)},${yFor(d[k]).toFixed(1)}`).join(' ');
            const dots = series.map((d, i) => `<circle cx="${xFor(i).toFixed(1)}" cy="${yFor(d[k]).toFixed(1)}" r="2.6" fill="${colors[k]}"/>`).join('');
            return `<polyline points="${pts}" fill="none" stroke="${colors[k]}" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>${dots}`;
        }).join('');
        const labels = series.map((d, i) => `<text x="${xFor(i).toFixed(1)}" y="${H - 8}" text-anchor="middle" font-size="9.5" fill="#9AAABB">${esc(d.label)}</text>`).join('');
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
        const sizeRows = sizes.map(s => {
            const stock = Number(s.stock || 0);
            let cls = 'in', label = 'В наличии (' + stock + ')';
            if (stock <= 0) { cls = 'out'; label = 'Нет в наличии'; }
            else if (stock <= 5) { cls = 'low'; label = 'Мало (' + stock + ')'; }
            return `<tr><td><strong>${esc(s.size)}</strong></td><td style="text-align:right"><span class="stock ${cls}">${esc(label)}</span></td></tr>`;
        }).join('');
        const altRows = alts.map(a => `
            <div class="alt-row">
                <div><div class="alt-store">${esc(a.store)}</div><div class="alt-note">${esc(a.note || '')}</div></div>
                <div class="alt-right"><div class="alt-price">${esc(fmtPrice(a.price, d.currency))}</div>
                ${a.url && a.url !== '#' ? `<a class="alt-link" href="${esc(a.url)}" target="_blank" rel="noopener">Открыть →</a>` : ''}</div>
            </div>`).join('');
        const promo = loyalty ? `
            <div class="dc-section"><h4>Программа лояльности</h4>
                <div class="promo">
                    <div><div class="promo-code">${esc(loyalty.code)}</div><div class="promo-note">${esc(loyalty.note || '')}</div></div>
                    ${loyalty.discount ? `<div class="promo-disc">${esc(loyalty.discount)}</div>` : ''}
                </div></div>` : '';

        return `
        <div class="card data-card fade-in">
            ${cardHead(d.productName || 'Товар', (d.brand ? d.brand + ' · ' : '') + 'Артикул: ' + (d.sku || objectUid), 'RETAIL')}
            <div class="dc-section">
                <div class="price-block"><span class="price-now">${esc(fmtPrice(d.price, d.currency))}</span></div>
                ${rating}
            </div>
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
        let gaugeColor = '#1E875A';
        if (fill >= 80) gaugeColor = '#C23A37';
        else if (fill >= 50) gaugeColor = '#A9760F';
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
        return `
        <div class="card data-card fade-in">
            ${cardHead(d.title || d.displayName || 'Объект', 'ID: ' + objectUid, 'GENERAL')}
            ${d.description ? `<div class="dc-section"><p>${esc(d.description)}</p></div>` : ''}
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
            toast('Сканер недоступен (нет связи с CDN). Используйте ручной ввод.', 'err');
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
    async function init() {
        const closeBtn = document.getElementById('scanner-close');
        if (closeBtn) closeBtn.addEventListener('click', closeScanner);

        checkHealth();
        setInterval(checkHealth, 30000);

        try { await loadMe(); } catch (_) { currentUser = null; }
        route();
    }

    document.addEventListener('DOMContentLoaded', init);
})();
