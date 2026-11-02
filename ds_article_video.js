(() => {
    'use strict';
    const section = document.querySelector('#article-video');
    if (!section) return;
    const settings = JSON.parse(document.querySelector('#article-video-settings').textContent);
    const key = `articleVideo:v1:${location.pathname}`;
    const legacyPaths = Array.isArray(settings.legacyPaths) ? settings.legacyPaths
        : (location.pathname.startsWith('/finance/') ? [`/journal/${location.pathname.slice('/finance/'.length)}`] : []);
    const legacyKeys = legacyPaths.map(path => `articleVideo:v1:${path}`);
    let storage;
    try { storage = window.sessionStorage; } catch (_) { /* Storage is optional. */ }
    const exitKey = `articleVideoExit:v1:${location.pathname}`;
    let deliberateReload = false;
    let released = false;
    const eraseTabState = () => {
        try {
            for (let i = (storage?.length || 0) - 1; i >= 0; i--) {
                const name = storage.key(i);
                if (name?.startsWith('articleVideo:v1:') || name?.startsWith('articleVideoExit:v1:')) storage.removeItem(name);
            }
            for (const name of [key, ...legacyKeys, exitKey]) storage?.removeItem(name);
        } catch (_) { /* A closed tab discards its sessionStorage normally. */ }
    };
    const releaseGrant = () => {
        if (released) return;
        released = true;
        eraseTabState();
        const grant = settings.release;
        if (!grant || typeof grant.path !== 'string' || !/^[a-f0-9]{64}$/.test(grant.key || '')
            || typeof grant.endpoint !== 'string' || !grant.endpoint.startsWith('/')) return;
        const endpoint = new URL(grant.endpoint, location.href);
        if (endpoint.origin !== new URL(location.href).origin) return;
        const body = new URLSearchParams({path:grant.path,key:grant.key});
        try {
            if (window.navigator?.sendBeacon?.(endpoint.href, body)) return;
            window.fetch?.(endpoint.href, {method:'POST',body,credentials:'same-origin',keepalive:true}).catch(() => {});
        } catch (_) { /* Server cleanup may be unavailable during browser shutdown. */ }
    };
    try {
        if (storage?.getItem(exitKey)) {
            const navigationType = window.performance?.getEntriesByType?.('navigation')?.[0]?.type;
            if (!settings.incoming && navigationType !== 'reload') {
                releaseGrant();
                section.hidden = true;
                return;
            }
            storage.removeItem(exitKey);
        }
    } catch (_) { /* Preserve storage-blocked playback behavior. */ }
    const policy = Object.assign({enabled:true,clean_url:false}, settings.playerSettings);
    if (!policy.enabled) {
        try { storage?.removeItem(key); } catch (_) { /* Ignore storage failures. */ }
        section.hidden = true;
        return;
    }
    const normalize = (value) => {
        if (!value || !settings.types.includes(value.type) || !settings.sources.includes(value.src)
            || !['', 'only'].includes(value.one) || !value.parts || typeof value.parts !== 'object') return null;
        const parts = {};
        for (const [part, token] of Object.entries(value.parts)) {
            if (!/^part([1-9]|[1-4][0-9]|50)$/.test(part) || typeof token !== 'string'
                || token.length > 8192 || !/^[a-zA-Z0-9+/=_\-.%:]+$/.test(token)) return null;
            parts[part] = token;
        }
        const keys = Object.keys(parts).sort((a, b) => Number(a.slice(4)) - Number(b.slice(4)));
        if (!keys.length || keys.length > 50) return null;
        return {parts, type: value.type, src: value.src, one: value.one,
            activePart: keys.includes(value.activePart) ? value.activePart : keys[0]};
    };
    let savedContext = null;
    try {
        let serialized = storage?.getItem(key);
        for (const oldKey of legacyKeys) if (!serialized) serialized = storage?.getItem(oldKey);
        savedContext = JSON.parse(serialized || 'null');
    } catch (_) { /* Ignore invalid saved state. */ }
    let state = normalize(settings.state);
    const identity = (value) => JSON.stringify([value.type,value.src,value.one,Object.keys(value.parts).sort().map(part=>[part,value.parts[part]])]);
    if (!settings.incoming && state) {
        const remembered = normalize(savedContext);
        // A server cookie alone must not revive a video in a new tab session.
        if (!remembered || identity(remembered) !== identity(state)) {
            try { storage?.removeItem(key); } catch (_) { /* Ignore storage failures. */ }
            section.hidden = true;
            return;
        }
        state = remembered;
    }
    if (!state) {
        if (settings.incoming) {
            try { storage?.removeItem(key); } catch (_) { /* Ignore storage failures. */ }
            section.hidden = false;
            section.querySelector('.video-stage').hidden = true;
            section.querySelector('.video-controls').hidden = true;
            section.querySelector('.video-search').hidden = true;
            section.querySelector('.video-back').hidden = true;
            section.querySelector('.video-status').textContent = 'This video link is invalid or unsupported.';
        }
        return;
    }
    section.hidden = false;
    const playerAdTemplate = document.querySelector('#above-article-video-ad');
    const playerAd = playerAdTemplate?.content.firstElementChild;
    if (playerAd) {
        // Keep provider scripts inert until this tab has a valid, visible player.
        const scripts = [...playerAd.querySelectorAll('script')].map(original => {
            const placeholder = document.createComment('advertisement script');
            original.replaceWith(placeholder);
            return {original, placeholder};
        });
        playerAdTemplate.replaceWith(playerAdTemplate.content);
        for (const {original, placeholder} of scripts) {
            const script = document.createElement('script');
            for (const attribute of original.attributes) script.setAttribute(attribute.name, attribute.value);
            if (!original.hasAttribute('async')) script.async = false;
            script.textContent = original.textContent;
            placeholder.replaceWith(script);
        }
    } else {
        playerAdTemplate?.remove();
    }
    let frame = section.querySelector('iframe');
    const parts = section.querySelector('.video-parts');
    const status = section.querySelector('.video-status');
    let loadingPart = null;
    let requestId = 0;
    let loadTimeout;
    let finishTimer;
    const keys = Object.keys(state.parts).sort((a, b) => Number(a.slice(4)) - Number(b.slice(4)));
    parts.style.setProperty('--video-part-count', String(keys.length));
    parts.classList.toggle('is-single-part', keys.length === 1);
    const buttons = new Map();
    const updateButtons = () => {
        for (const [part, button] of buttons) {
            const busy = part === loadingPart;
            button.setAttribute('aria-pressed', String(part === state.activePart));
            button.setAttribute('aria-busy', String(busy));
            button.disabled = busy;
            button.classList.toggle('is-loading', busy);
        }
        section.querySelector('.video-stage').setAttribute('aria-busy', String(loadingPart !== null));
    };
    const save = () => {
        try {
            if (!storage) return false;
            const serialized = JSON.stringify(state);
            storage.setItem(key, serialized);
            const saved = storage.getItem(key) === serialized;
            if (saved) for (const oldKey of legacyKeys) storage.removeItem(oldKey);
            return saved;
        } catch (_) { return false; }
    };
    const syncUrl = (saved) => {
        const url = new URL(location.href);
        url.searchParams.delete('vt');
        url.searchParams.delete('traffic_origin');
        if (policy.clean_url && saved) {
            for (const name of [...url.searchParams.keys()]) {
                if (name === 'v' || /^part\d+$/.test(name) || /^utm_/i.test(name) || ['activePart','type','src','one'].includes(name)) url.searchParams.delete(name);
            }
            if (url.hash === '#article-video') url.hash = '';
            history.replaceState(null, '', url);
            return;
        }
        for (const name of [...url.searchParams.keys()]) {
            if (name === 'v' || /^part\d+$/.test(name)) url.searchParams.delete(name);
        }
        for (const [part, token] of Object.entries(state.parts)) url.searchParams.set(part === 'part1' ? 'v' : part, token);
        for (const name of ['activePart', 'type', 'src', 'one']) {
            if (state[name]) url.searchParams.set(name, state[name]); else url.searchParams.delete(name);
        }
        history.replaceState(null, '', url);
    };
    const render = () => {
        syncUrl(save());
        status.textContent = typeof settings.notice === 'string' && settings.notice.trim()
            ? settings.notice.trim()
            : 'The episode is divided into smaller parts to reduce file size, ensuring faster and more stable video loading, especially for weak network connections.';
        updateButtons();
    };
    const refresh = section.querySelector('.video-refresh');
    if (refresh) refresh.addEventListener('click', () => {
        if (refresh.disabled) return;
        syncUrl(save());
        refresh.disabled = true;
        refresh.setAttribute('aria-busy', 'true');
        ++requestId;
        clearTimeout(loadTimeout);
        clearTimeout(finishTimer);
        loadingPart = state.activePart;
        updateButtons();
        deliberateReload = true;
        location.reload();
    });
    const loadFrame = () => {
        const id = ++requestId;
        clearTimeout(loadTimeout);
        clearTimeout(finishTimer);
        loadingPart = state.activePart;
        updateButtons();
        const started = performance.now();
        let settled = false;
        const finish = (message) => {
            if (id !== requestId || settled) return;
            settled = true;
            clearTimeout(loadTimeout);
            finishTimer = setTimeout(() => {
                if (id !== requestId) return;
                loadingPart = null;
                updateButtons();
                if (message) status.textContent = message;
            }, Math.max(0, 240 - (performance.now() - started)));
        };
        const host = settings.playerHost;
        const url = settings.playback === 'local'
            ? new URL(`/hls/${state.type}.php`, location.href)
            : new URL(`https://${host}/hls/${state.type}.php`);
        url.searchParams.set('v', state.parts[state.activePart]);
        url.searchParams.set('embed_url', window.location.href);
        // A fresh frame prevents an older navigation from completing a newer part's loading state.
        const nextFrame = frame.cloneNode(false);
        nextFrame.hidden = false;
        nextFrame.addEventListener('load', () => finish(), {once: true});
        nextFrame.addEventListener('error', () => finish('The video player could not be loaded. Reload the page to try again.'), {once: true});
        nextFrame.src = url.href;
        frame.replaceWith(nextFrame);
        frame = nextFrame;
        loadTimeout = setTimeout(() => finish('The player is taking longer than expected. Reload the page or choose another part.'), 15000);
    };
    for (const part of keys) {
        const button = document.createElement('button');
        button.type = 'button';
        button.dataset.part = part;
        const spinner = section.querySelector('.video-spinner-template').content.firstElementChild.cloneNode(true);
        spinner.classList.add('video-part-spinner');
        const play = section.querySelector('.video-play-template').content.firstElementChild.cloneNode(true);
        play.classList.add('video-part-play');
        const label = document.createElement('span');
        label.textContent = keys.length === 1 ? 'Full video' : `Part ${Number(part.slice(4))}`;
        button.append(spinner, play, label);
        button.addEventListener('click', () => {
            if (button.disabled || part === state.activePart) return;
            state.activePart = part;
            const saved = save();
            syncUrl(saved);
            loadingPart = part;
            updateButtons();
            deliberateReload = true;
            location.reload();
        });
        buttons.set(part, button);
        parts.appendChild(button);
    }
    const sourceLinks = settings.sourceLinks[state.src];
    section.querySelector('.video-search').action = sourceLinks.search;
    section.querySelector('.video-search input').name = sourceLinks.search_param || 's';
    section.querySelector('.video-back').href = sourceLinks.home;
    section.querySelector('.video-back span').textContent = sourceLinks.label;
    // Replace the header only after a configured source logo has loaded successfully.
    if (settings.logos[state.src]) {
        const brand = document.querySelector('.topbar .brand');
        const logo = document.createElement('img');
        logo.className = 'source-brand-logo';
        logo.alt = `Video source: ${state.src}`;
        logo.width = 220;
        logo.height = 52;
        logo.referrerPolicy = 'no-referrer';
        logo.addEventListener('load', () => {
            if (!brand) return;
            brand.replaceChildren(logo);
            brand.classList.add('source-brand');
        }, {once: true});
        logo.src = settings.logos[state.src];
    }
    render();
    loadFrame();
    const leavingPage = (url) => {
        try {
            const target = new URL(url, location.href);
            const current = new URL(location.href);
            return target.origin !== current.origin || target.pathname !== current.pathname;
        } catch (_) { return false; }
    };
    document.addEventListener?.('click', (event) => {
        if (event.defaultPrevented || event.button || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
        const link = event.target?.closest?.('a[href]');
        if (!link || link.hasAttribute('download') || (link.target && link.target !== '_self')) return;
        if (leavingPage(link.href)) releaseGrant();
    });
    document.addEventListener?.('submit', (event) => {
        const form = event.target;
        if (!event.defaultPrevented && (!form.target || form.target === '_self') && leavingPage(form.action)) releaseGrant();
    });
    const navigation = window.navigation;
    navigation?.addEventListener?.('navigate', (event) => {
        if (event.destination.sameDocument) return;
        if (event.navigationType === 'reload') deliberateReload = true;
        else if (leavingPage(event.destination.url)) releaseGrant();
    });
    window.addEventListener?.('pagehide', () => {
        if (deliberateReload) return;
        // Native reload and closing are indistinguishable here, even with the Navigation API.
        try { storage?.setItem(exitKey, 'left'); } catch (_) {}
    });
    window.addEventListener?.('pageshow', (event) => {
        if (!event.persisted) return;
        let departed = released;
        try { departed = departed || Boolean(storage?.getItem(exitKey)); } catch (_) {}
        if (departed) {
            releaseGrant();
            clearTimeout(loadTimeout);
            section.hidden = true;
            if (playerAd) playerAd.hidden = true;
            frame.src = 'about:blank';
        }
    });
})();
