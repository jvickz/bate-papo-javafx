(() => {
  "use strict";

  const namePattern = /^[A-Za-z0-9_]{3,24}$/;
  const timeFormat = new Intl.DateTimeFormat("pt-BR", { dateStyle: "short", timeStyle: "short" });
  const pageSize = 50;
  let dispose = null;

  function node(tag, text, className) {
    const element = document.createElement(tag);
    if (text !== undefined) element.textContent = text;
    if (className) element.className = className;
    return element;
  }

  function open({ username, token, onExpired }) {
    if (dispose) dispose();
    const lifetime = new AbortController();
    const dialog = document.createElement("dialog");
    dialog.className = "dm-dialog";
    dialog.setAttribute("aria-labelledby", "dm-title");
    // Only static markup is used here. Account names and messages are inserted as text.
    dialog.innerHTML = `
      <header class="dm-header"><div><h2 id="dm-title">Mensagens privadas</h2>
        <p>Conversas entre contas, com histórico salvo no servidor.</p></div>
        <button class="dm-close" type="button" aria-label="Fechar mensagens privadas">Fechar</button></header>
      <div class="dm-layout">
        <aside class="dm-sidebar" aria-label="Caixa de entrada">
          <form class="dm-open-form"><label for="dm-peer">Abrir conversa com</label>
            <div><input id="dm-peer" placeholder="Nome de usuário" autocomplete="off" autocapitalize="none" maxlength="25" spellcheck="false" required>
            <button type="submit">Abrir</button></div></form>
          <p class="dm-list-status" role="status"></p>
          <ul class="dm-threads" aria-label="Conversas"></ul>
        </aside>
        <section class="dm-thread" aria-labelledby="dm-heading">
          <div class="dm-thread-header"><h3 id="dm-heading" tabindex="-1">Escolha uma conversa</h3>
            <button type="button" class="dm-latest" hidden>Ir às recentes</button></div>
          <button type="button" class="dm-older" hidden>Mensagens anteriores</button>
          <ol class="dm-history" tabindex="0" aria-label="Histórico da conversa"></ol>
          <p class="dm-status" role="status"></p>
          <form class="dm-compose" hidden><label for="dm-text">Mensagem privada</label>
            <div><textarea id="dm-text" rows="2" placeholder="Escreva uma mensagem…"></textarea>
            <button type="submit">Enviar</button></div></form>
        </section>
      </div>`;
    const find = selector => dialog.querySelector(selector);
    const recipient = find("#dm-peer");
    const list = find(".dm-threads");
    const history = find(".dm-history");
    const heading = find("#dm-heading");
    const notice = find(".dm-status");
    const listNotice = find(".dm-list-status");
    const older = find(".dm-older");
    const latest = find(".dm-latest");
    const composer = find(".dm-compose");
    const editor = find("#dm-text");
    const sendButton = composer.querySelector("button");
    const drafts = new Map();
    let peer = "", generation = 0, page = [], browsing = false, pageBusy = false;
    let ready = false, readThrough = 0, readInFlight = false;
    let catalog = [], catalogBusy = false;
    let etag = "";

    function active(version) {
      return !lifetime.signal.aborted && version === generation;
    }

    async function request(path, body, headers = {}) {
      const timeout = AbortSignal.timeout(30000);
      const response = await fetch(path, {
        method: body ? "POST" : "GET",
        mode: "same-origin", credentials: "omit", redirect: "error", cache: "no-store",
        headers: { Authorization: "Bearer " + token, ...headers, ...(body ? { "Content-Type": "application/json" } : {}) },
        body: body ? JSON.stringify(body) : undefined,
        signal: AbortSignal.any([lifetime.signal, timeout])
      });
      if (response.status === 401 && !lifetime.signal.aborted) {
        close();
        onExpired?.();
      }
      if (!response.ok && response.status !== 304) {
        const details = await response.json().catch(() => null);
        throw new Error(details?.error || "Não foi possível acessar as mensagens privadas.");
      }
      return response;
    }

    function report(error, destination) {
      if (lifetime.signal.aborted) return;
      destination.textContent = error instanceof TypeError || error.name === "TimeoutError"
        ? "Falha na conexão. Tente novamente." : error.message;
    }

    function controls() {
      const draft = drafts.get(peer.toLowerCase());
      const count = Array.from(editor.value).length;
      composer.hidden = !ready;
      sendButton.disabled = !ready || draft?.sending || !editor.value.trim() || count > 2000;
      sendButton.textContent = draft?.sending ? "Enviando…" : "Enviar";
      editor.setCustomValidity(count > 2000 ? "Use até 2.000 caracteres." : "");
      if (count > 2000) notice.textContent = "Use até 2.000 caracteres.";
      else if (notice.textContent === "Use até 2.000 caracteres.") notice.textContent = "";
    }

    function renderThreads() {
      const focusPeer = list.contains(document.activeElement) ? document.activeElement.dataset.peer : "";
      const fragment = document.createDocumentFragment();
      for (const item of catalog) {
        const button = node("button");
        button.type = "button";
        button.dataset.peer = item.peer;
        button.setAttribute("aria-current", String(item.peer.toLowerCase() === peer.toLowerCase()));
        button.append(node("strong", "@" + item.peer));
        if (item.unread) button.append(node("span", "Nova", "dm-unread"));
        button.append(node("small", item.preview));
        const li = node("li");
        li.append(button);
        fragment.append(li);
      }
      list.replaceChildren(fragment);
      if (focusPeer) [...list.querySelectorAll("button")].find(b => b.dataset.peer === focusPeer)?.focus();
    }

    async function refreshCatalog() {
      if (catalogBusy || lifetime.signal.aborted || document.hidden) return;
      catalogBusy = true;
      try {
        const response = await request("/direct/threads", null, etag ? { "If-None-Match": etag } : {});
        if (response.status !== 304) {
          const rows = await response.json();
          if (lifetime.signal.aborted) return;
          etag = response.headers.get("ETag") || "";
          catalog = rows;
          renderThreads();
        }
        listNotice.textContent = catalog.length ? "" : "Nenhuma conversa ainda.";
        const row = catalog.find(t => t.peer.toLowerCase() === peer.toLowerCase());
        if (ready && row && row.latestId > (page.at(-1)?.id || 0)) {
          if (!browsing && atBottom()) await loadPage(false);
          else latest.hidden = false;
        }
      } catch (error) { report(error, listNotice); }
      finally { catalogBusy = false; }
    }

    function path(kind, name = peer) {
      return "/direct/" + kind + "/" + encodeURIComponent(name);
    }

    function atBottom() {
      return history.scrollHeight - history.scrollTop - history.clientHeight < 30;
    }

    async function markRead() {
      const version = generation;
      const through = page.at(-1)?.id;
      if (!ready || readInFlight || browsing || !through || through <= readThrough ||
          document.hidden || !document.hasFocus() || !atBottom()) return;
      readInFlight = true;
      try {
        await request(path("read"), { through });
        if (!active(version)) return;
        readThrough = through;
        const row = catalog.find(t => t.peer.toLowerCase() === peer.toLowerCase());
        if (row?.unread && row.latestId <= through) { row.unread = false; renderThreads(); }
      } catch (error) { if (active(version)) report(error, notice); }
      finally {
        if (active(version)) {
          readInFlight = false;
          if (readThrough === through && page.at(-1)?.id > through) void markRead();
        }
      }
    }

    async function loadPage(previous) {
      if (pageBusy || !peer) return;
      const version = generation;
      pageBusy = true;
      older.disabled = latest.disabled = true;
      try {
        const query = previous ? "?before=" + page[0].id : "";
        const response = await request(path("messages") + query);
        const rows = await response.json();
        if (!active(version)) return;
        ready = true;
        if (previous && !rows.length) { older.hidden = true; return; }
        const wasReady = !composer.hidden;
        const latestSeen = page.at(-1)?.id || 0;
        browsing = previous;
        page = rows;
        const fragment = document.createDocumentFragment();
        for (const m of rows) {
          const mine = m.sender.toLowerCase() === username.toLowerCase();
          const line = node("li", undefined, mine ? "dm-line dm-mine" : "dm-line");
          const meta = node("div", undefined, "dm-meta");
          const stamp = node("time", timeFormat.format(new Date(m.time)));
          stamp.dateTime = m.time;
          meta.append(node("strong", mine ? "Você" : "@" + m.sender), stamp);
          line.append(meta, node("p", m.text));
          fragment.append(line);
        }
        history.replaceChildren(fragment);
        older.hidden = rows.length < pageSize;
        latest.hidden = !previous;
        notice.textContent = rows.length ? "" : "Envie a primeira mensagem.";
        if (!previous && wasReady && rows.some(m => m.id > latestSeen && m.sender.toLowerCase() !== username.toLowerCase())) {
          notice.textContent = "Novas mensagens recebidas.";
        }
        controls();
        history.scrollTop = previous ? 0 : history.scrollHeight;
        void markRead();
      } catch (error) { if (active(version)) report(error, notice); }
      finally {
        if (active(version)) {
          pageBusy = false;
          older.disabled = latest.disabled = false;
        }
      }
    }

    function choose(name) {
      name = name.trim().replace(/^@/, "");
      if (!namePattern.test(name) || name.toLowerCase() === username.toLowerCase()) {
        listNotice.textContent = "Digite o nome de outra conta, com 3 a 24 letras, números ou _.";
        recipient.focus();
        return;
      }
      ++generation;
      peer = name;
      page = [];
      pageBusy = ready = browsing = readInFlight = false;
      readThrough = 0;
      recipient.value = "";
      history.replaceChildren();
      heading.textContent = "@" + name;
      notice.textContent = "Carregando conversa…";
      editor.value = drafts.get(name.toLowerCase())?.text || "";
      older.hidden = latest.hidden = true;
      renderThreads();
      controls();
      heading.focus();
      void loadPage(false);
    }

    editor.addEventListener("input", () => {
      const key = peer.toLowerCase();
      const draft = drafts.get(key) || {};
      draft.text = editor.value;
      drafts.set(key, draft);
      controls();
    });

    composer.addEventListener("submit", async event => {
      event.preventDefault();
      if (sendButton.disabled) return;
      const destination = peer;
      const key = peer.toLowerCase();
      const draft = drafts.get(key);
      const body = editor.value;
      // Keep the same id after an ambiguous response, so retrying cannot duplicate a saved message.
      if (draft.lastText !== body) { draft.lastText = body; draft.id = crypto.randomUUID(); }
      draft.sending = true;
      controls();
      try {
        await request(path("messages", destination), { clientId: draft.id, text: body });
        if (lifetime.signal.aborted) return;
        if (draft.text === body) draft.text = "";
        draft.lastText = draft.id = undefined;
        if (key === peer.toLowerCase()) {
          editor.value = draft.text;
          await loadPage(false);
          if (key === peer.toLowerCase()) editor.focus();
        }
        void refreshCatalog();
      } catch (error) {
        if (key === peer.toLowerCase()) report(error, notice);
      } finally {
        draft.sending = false;
        if (!lifetime.signal.aborted) controls();
      }
    });
    editor.addEventListener("keydown", event => {
      if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
        event.preventDefault();
        if (!sendButton.disabled) composer.requestSubmit();
      }
    });
    find(".dm-open-form").addEventListener("submit", event => { event.preventDefault(); choose(recipient.value); });
    list.addEventListener("click", event => {
      const button = event.target.closest("button");
      if (button) choose(button.dataset.peer);
    });
    older.addEventListener("click", () => void loadPage(true));
    latest.addEventListener("click", () => void loadPage(false));
    history.addEventListener("scroll", () => void markRead());
    window.addEventListener("focus", markRead, { signal: lifetime.signal });
    document.addEventListener("visibilitychange", () => {
      if (!document.hidden) { void refreshCatalog(); void markRead(); }
    }, { signal: lifetime.signal });
    const poll = setInterval(refreshCatalog, 7000);
    function close() {
      if (lifetime.signal.aborted) return;
      lifetime.abort();
      clearInterval(poll);
      token = "";
      drafts.clear();
      if (dialog.open) dialog.close();
      dialog.remove();
      dispose = null;
    }
    dispose = close;
    find(".dm-close").addEventListener("click", close);
    dialog.addEventListener("close", close);
    document.body.append(dialog);
    dialog.showModal();
    recipient.focus();
    void refreshCatalog();
  }

  window.WavecoveDirect = Object.freeze({ open, close: () => dispose?.() });
})();
