(() => {
  "use strict";

  const el = id => document.getElementById(id);
  const username = el("auth-username");
  const password = el("auth-password");
  const editor = el("message-input");
  const feed = el("messages");
  const history = new Map();
  const drafts = new Map();
  const timeFormat = new Intl.DateTimeFormat("pt-BR", {hour: "2-digit", minute: "2-digit"});
  const namePattern = /^[A-Za-z0-9_]{3,24}$/;
  const channelPattern = /^[a-z0-9][a-z0-9-]{1,23}$/;
  const compact = window.matchMedia("(max-width: 760px)");
  const secure = location.protocol === "https:" || (location.protocol === "http:" &&
    ["localhost", "127.0.0.1", "[::1]"].includes(location.hostname));

  let registering = false;
  let loginRequest = null;
  let session = null;
  let socket = null;
  let resume = null;
  let state = "offline";
  let channel = "geral";
  let catalog = [];
  let change = null;
  let sending = null;
  let responseTimer;
  let watchdog;

  function setMode(create) {
    registering = create;
    for (const [id, active] of [["login-tab", !create], ["register-tab", create]]) {
      el(id).setAttribute("aria-selected", String(active));
      el(id).tabIndex = active ? 0 : -1;
    }
    el("auth-title").textContent = create ? "Crie sua conta" : "Entre na sua conta";
    el("auth-description").textContent = create ? "Escolha o nome que vai aparecer nos canais." :
      "Use sua conta do Wavecove para acessar os canais.";
    password.autocomplete = create ? "new-password" : "current-password";
    el("auth-submit-label").textContent = create ? "Criar conta" : "Entrar";
    el("auth-help").hidden = !create;
    el("auth-error").textContent = secure ? "" : "Abra o site por HTTPS para entrar com segurança.";
    el("auth-submit").disabled = !secure;
  }

  function showPassword(show) {
    password.type = show ? "text" : "password";
    const button = el("password-toggle");
    button.setAttribute("aria-pressed", String(show));
    button.setAttribute("aria-label", show ? "Ocultar senha" : "Mostrar senha");
    button.title = button.getAttribute("aria-label");
  }

  function busyAuth(busy) {
    for (const id of ["auth-submit", "login-tab", "register-tab", "auth-username", "auth-password"]) {
      el(id).disabled = busy;
    }
    el("auth-submit").disabled = busy || !secure;
    el("auth-form").setAttribute("aria-busy", String(busy));
    el("auth-submit-label").textContent = busy ? (registering ? "Criando conta…" : "Entrando…") :
      (registering ? "Criar conta" : "Entrar");
  }

  function revoke(token) {
    return fetch("/accounts/logout", {
      method: "POST", headers: {Authorization: "Bearer " + token},
      credentials: "omit", redirect: "error", keepalive: true
    }).catch(() => {});
  }

  async function authenticate(event) {
    event.preventDefault();
    if (loginRequest || session || !secure) return;
    const name = username.value.trim();
    const length = Array.from(password.value).length;
    if (!namePattern.test(name) || !password.value.trim() || length < 12 || length > 128) {
      el("auth-error").textContent = !namePattern.test(name) ?
        "Use de 3 a 24 letras sem acento, números ou sublinhado no usuário." : "Use uma senha de 12 a 128 caracteres.";
      (!namePattern.test(name) ? username : password).focus();
      return;
    }
    el("auth-error").textContent = "";
    const request = new AbortController();
    loginRequest = request;
    const timeout = setTimeout(() => request.abort(), 90000);
    busyAuth(true);
    try {
      const result = await fetch(registering ? "/accounts/register" : "/accounts/login", {
        method: "POST", headers: {"Content-Type": "application/json", Accept: "application/json"},
        body: JSON.stringify({username: name, password: password.value}), credentials: "omit",
        cache: "no-store", redirect: "error", signal: request.signal
      });
      const data = await result.json().catch(() => ({}));
      if (!result.ok) {
        if (request === loginRequest) el("auth-error").textContent = typeof data.error === "string" ? data.error :
          "O acesso às contas está indisponível. Tente novamente em instantes.";
        return;
      }
      if (typeof data.username !== "string" || !namePattern.test(data.username) || data.username.toLowerCase() !== name.toLowerCase() ||
          typeof data.token !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(data.token)) throw new Error();
      if (request !== loginRequest) { revoke(data.token); return; }
      session = {name: data.username, token: data.token};
      password.value = "";
      showPassword(false);
      el("self-name").textContent = session.name;
      el("self-initial").textContent = session.name[0].toUpperCase();
      el("auth-view").hidden = true;
      el("chat-view").hidden = false;
      document.body.classList.add("in-chat");
      connect();
    } catch {
      if (request === loginRequest) el("auth-error").textContent = "Não foi possível acessar o servidor. Tente novamente.";
    } finally {
      clearTimeout(timeout);
      if (request === loginRequest) { loginRequest = null; busyAuth(false); }
    }
  }

  function setState(next) {
    state = next;
    const status = el("connection-status");
    status.dataset.state = next;
    status.textContent = {online: "Conectado", connecting: "Conectando…", offline: "Desconectado"}[next];
    el("retry-button").hidden = next !== "offline" || !session;
    refreshControls();
  }

  function refreshControls() {
    const online = state === "online" && !change;
    const usable = online && !sending;
    el("channel-create").disabled = !usable;
    el("channel-name").disabled = !usable;
    for (const button of el("channels").querySelectorAll("button")) button.disabled = !usable;
    const length = Array.from(editor.value).length;
    el("message-count").textContent = length + " / 2000";
    el("send-button").disabled = !usable || !editor.value.trim() || length > 2000;
    // Keep the submitted draft intact until the server echoes or rejects it.
    editor.readOnly = !usable;
  }

  function clearResponse() {
    clearTimeout(responseTimer);
    change = null;
    sending = null;
  }

  function disconnect(reason) {
    const ws = socket;
    socket = null;
    if (ws && ws.readyState < WebSocket.CLOSING) ws.close();
    clearTimeout(watchdog);
    if (sending) reason += " A entrega da última mensagem ficou sem confirmação.";
    clearResponse();
    setState("offline");
    showMembers([]);
    el("chat-notice").textContent = reason;
  }

  function resetAccount(feedback = "") {
    if (loginRequest) loginRequest.abort();
    loginRequest = null;
    const old = session;
    session = null;
    window.WavecoveDirect?.close();
    disconnect("");
    if (old) revoke(old.token);
    resume = null;
    history.clear();
    drafts.clear();
    catalog = [];
    channel = "geral";
    editor.value = "";
    el("channel-name").value = "";
    el("self-name").textContent = "";
    el("self-initial").textContent = "";
    renderChannel();
    el("chat-view").hidden = true;
    el("auth-view").hidden = false;
    document.body.classList.remove("in-chat");
    password.value = "";
    showPassword(false);
    busyAuth(false);
    setMode(false);
    if (feedback) el("auth-error").textContent = feedback;
    username.focus();
  }

  function connect() {
    if (!session || socket) return;
    setState("connecting");
    el("chat-notice").textContent = "";
    const endpoint = new URL("/chat", location.href);
    endpoint.protocol = location.protocol === "https:" ? "wss:" : "ws:";
    let ws;
    try { ws = new WebSocket(endpoint); }
    catch { disconnect("Não foi possível abrir a conexão. Tente reconectar."); return; }
    socket = ws;
    function deadline(ms) {
      clearTimeout(watchdog);
      watchdog = setTimeout(() => {
        if (ws === socket) disconnect("O servidor demorou para responder. Tente reconectar.");
      }, ms);
    }
    deadline(90000);
    ws.addEventListener("open", () => {
      if (socket !== ws) return;
      transmit({type: "hello", id: "channels-v1", name: session.name, token: session.token,
        session: resume, channel, users: ["delete-v1"]});
    });
    ws.addEventListener("message", event => {
      if (socket !== ws) return;
      deadline(55000);
      try {
        if (typeof event.data !== "string" || event.data.length > 16384) throw new Error();
        receive(JSON.parse(event.data));
      } catch {
        disconnect("O servidor enviou uma resposta inválida. Tente reconectar.");
      }
    });
    ws.addEventListener("close", () => {
      if (socket === ws) disconnect("A conexão foi interrompida. Clique em Reconectar para voltar.");
    });
    ws.addEventListener("error", () => {
      if (socket === ws) disconnect("Não foi possível manter a conexão. Confira sua rede e tente reconectar.");
    });
  }

  function transmit(event) {
    if (socket && socket.readyState === WebSocket.OPEN) {
      try { socket.send(JSON.stringify(event)); return; } catch { /* Socket closed between events. */ }
    }
    disconnect("A conexão foi interrompida. Tente reconectar.");
  }

  function receive(event) {
    if (event.type === "ping") { transmit({type: "pong"}); return; }
    if (event.type === "error") {
      const text = event.text || "O servidor não conseguiu concluir a solicitação.";
      if (event.id === "auth" || event.id === "name") { resetAccount(text); return; }
      if (event.id === "rate" || event.id === "full") { disconnect(text); return; }
      if (event.id === change?.id || event.id === sending?.id) {
        clearResponse();
        refreshControls();
      }
      el("chat-notice").textContent = text;
      return;
    }
    if (event.type === "welcome" && state === "connecting") {
      resume = event.session;
      channel = channelPattern.test(event.channel) ? event.channel : "geral";
      renderChannel();
      setState("online");
      editor.focus();
      return;
    }
    if (state !== "online") return;
    if (event.type === "channels" && Array.isArray(event.users)) {
      catalog = event.users.filter(name => typeof name === "string" && channelPattern.test(name));
      showChannels();
      return;
    }
    if (event.type === "joined" && change && event.id === change.id && event.channel === change.channel) {
      if (change.type === "create") el("channel-name").value = "";
      clearResponse();
      channel = event.channel;
      el("chat-notice").textContent = "";
      showMembers([]);
      renderChannel();
      if (compact.matches) el("channel-drawer").open = false;
      editor.focus();
      return;
    }
    if (event.channel !== channel) return;
    switch (event.type) {
      case "presence":
        if (Array.isArray(event.users)) showMembers(event.users);
        break;
      case "message":
        if (typeof event.name !== "string" || typeof event.text !== "string") return;
        if (sending && event.name === session.name && event.id === sending.id) {
          if (editor.value === sending.text) {
            editor.value = "";
            drafts.delete(channel);
          }
          clearResponse();
          refreshControls();
        }
        addMessage({name: event.name, text: event.text, time: event.time, reference: event.session});
        break;
      case "retained":
        if (Array.isArray(event.users)) {
          const retained = new Set(event.users);
          removeMessages(row => row.reference && !retained.has(row.reference));
        }
        break;
      case "deleted":
        if (typeof event.id === "string") removeMessages(row => row.reference === event.id);
        break;
    }
  }

  function removeMessages(predicate) {
    const rows = history.get(channel) || [];
    const kept = rows.filter(row => !predicate(row));
    if (kept.length !== rows.length) { history.set(channel, kept); showHistory(); }
  }

  function node(tag, className, text) {
    const element = document.createElement(tag);
    element.className = className;
    element.textContent = text;
    return element;
  }

  function messageNode(row) {
    const item = node("li", "message" + (row.name === session.name ? " own" : ""), "");
    const avatar = node("span", "message-avatar", row.name.charAt(0).toUpperCase());
    avatar.setAttribute("aria-hidden", "true");
    const content = node("div", "message-content", "");
    const author = node("strong", "message-author", row.name);
    const time = node("time", "message-time", "");
    const date = new Date(row.time);
    if (Number.isFinite(date.getTime())) {
      time.dateTime = date.toISOString();
      time.textContent = timeFormat.format(date);
      time.title = date.toLocaleString("pt-BR");
    }
    content.append(author, time, node("p", "message-body", row.text));
    item.append(avatar, content);
    return item;
  }

  function addMessage(row) {
    const rows = history.get(channel) || [];
    const follow = feed.scrollTop + feed.clientHeight >= feed.scrollHeight - 48 || row.name === session.name;
    rows.push(row);
    history.set(channel, rows);
    feed.append(messageNode(row));
    if (rows.length > 500) { rows.shift(); feed.firstElementChild.remove(); }
    el("empty-chat").hidden = true;
    if (follow) feed.scrollTop = feed.scrollHeight;
  }

  function showHistory() {
    const rows = history.get(channel) || [];
    feed.replaceChildren(...rows.map(messageNode));
    el("empty-chat").hidden = rows.length !== 0;
    feed.scrollTop = feed.scrollHeight;
  }

  function showChannels() {
    const channels = el("channels");
    channels.replaceChildren(...catalog.map(name => {
      const item = document.createElement("li");
      const button = node("button", name === channel ? "active" : "", "# " + name);
      button.type = "button";
      button.dataset.channel = name;
      if (channel === name) button.setAttribute("aria-current", "true");
      button.disabled = state !== "online" || !!change || !!sending;
      item.append(button);
      return item;
    }));
  }

  function showMembers(names) {
    el("members").replaceChildren(...names.map(name => node("li", "", name)));
    el("member-count").textContent = String(names.length);
  }

  function renderChannel() {
    el("channel-title").textContent = "#" + channel;
    editor.placeholder = "Mensagem em #" + channel;
    editor.value = drafts.get(channel) || "";
    showChannels();
    showHistory();
    refreshControls();
  }

  function requestChange(type, name) {
    if (state !== "online" || change || sending || (type === "join" && channel === name)) return;
    if (!channelPattern.test(name)) {
      el("chat-notice").textContent = "Use de 2 a 24 letras minúsculas, números ou hífens no nome do canal.";
      el("channel-name").focus();
      return;
    }
    change = {type, id: crypto.randomUUID(), channel: name};
    el("chat-notice").textContent = "Abrindo #" + name + "…";
    refreshControls();
    responseTimer = setTimeout(() => disconnect("A troca de canal ficou sem confirmação. Tente reconectar."), 15000);
    transmit(change);
  }

  function submitMessage(event) {
    event.preventDefault();
    if (el("send-button").disabled) return;
    const text = editor.value;
    if (/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/u.test(text)) {
      el("chat-notice").textContent = "A mensagem contém um caractere de controle inválido.";
      return;
    }
    sending = {type: "message", id: crypto.randomUUID(), text, channel};
    el("chat-notice").textContent = "";
    refreshControls();
    responseTimer = setTimeout(() => disconnect("O servidor demorou para responder."), 15000);
    transmit(sending);
  }

  el("auth-form").addEventListener("submit", authenticate);
  for (const [id, create] of [["login-tab", false], ["register-tab", true]]) {
    el(id).addEventListener("click", () => { if (registering !== create) { password.value = ""; showPassword(false); setMode(create); } });
    el(id).addEventListener("keydown", event => {
      const selection = {ArrowLeft: !create, ArrowRight: !create, Home: false, End: true}[event.key];
      if (selection === undefined) return;
      event.preventDefault();
      const tab = el(selection ? "register-tab" : "login-tab");
      tab.click();
      tab.focus();
    });
  }
  el("password-toggle").addEventListener("click", () => showPassword(password.type === "password"));
  el("logout-button").addEventListener("click", () => resetAccount());
  el("open-dms").addEventListener("click", () => {
    const owner = session;
    if (!owner) return;
    window.WavecoveDirect.open({
      username: owner.name, token: owner.token,
      onExpired: () => { if (owner === session) resetAccount("Sua sessão expirou. Entre novamente."); }
    });
  });
  el("retry-button").addEventListener("click", connect);
  el("channels").addEventListener("click", event => {
    const button = event.target.closest("button[data-channel]");
    if (button) requestChange("join", button.dataset.channel);
  });
  el("channel-form").addEventListener("submit", event => {
    event.preventDefault();
    requestChange("create", el("channel-name").value.trim().toLowerCase());
  });
  editor.addEventListener("input", () => {
    if (editor.value) drafts.set(channel, editor.value);
    else drafts.delete(channel);
    refreshControls();
  });
  editor.addEventListener("keydown", event => {
    if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      if (!el("send-button").disabled) el("composer-form").requestSubmit();
    }
  });
  el("composer-form").addEventListener("submit", submitMessage);
  window.addEventListener("pagehide", () => { if (session || loginRequest) resetAccount(); });
  function fitDrawers() {
    el("channel-drawer").open = el("member-drawer").open = !compact.matches;
  }
  for (const [drawer, other] of [["channel-drawer", "member-drawer"], ["member-drawer", "channel-drawer"]]) {
    el(drawer).addEventListener("toggle", () => {
      if (compact.matches && el(drawer).open) el(other).open = false;
    });
  }
  compact.addEventListener("change", fitDrawers);
  fitDrawers();
  setMode(false);
  refreshControls();
})();
