(() => {
  "use strict";

  const INVITE = /^WC1\.([0-9a-f]{32})\.([A-Za-z0-9_-]{43})$/;
  const clock = new Intl.DateTimeFormat("pt-BR", {hour: "2-digit", minute: "2-digit"});
  const secure = location.protocol === "https:" || (location.protocol === "http:" &&
    ["localhost", "127.0.0.1", "[::1]"].includes(location.hostname));
  let currentWindow = null;

  function stripInvitation() {
    const hash = location.hash;
    if (!hash.startsWith("#room=") && !hash.startsWith("#WC1.")) return null;
    history.replaceState(history.state, "", location.pathname + location.search);
    return hash.startsWith("#room=") ? hash.slice(6) : hash.slice(1);
  }

  function encode(bytes) {
    return btoa(String.fromCharCode(...bytes)).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
  }

  function decode(text) {
    return Uint8Array.from(atob(text.replaceAll("-", "+").replaceAll("_", "/")), ch => ch.charCodeAt(0));
  }

  function parseInvite(input) {
    let value = input.trim();
    if (value.startsWith("https://") || value.startsWith("http://")) {
      const url = new URL(value);
      if (url.origin !== location.origin) throw new Error("server");
      value = url.hash.startsWith("#room=") ? url.hash.slice(6) : url.hash.slice(1);
    }
    const found = INVITE.exec(value);
    if (!found) throw new Error("invalid");
    const key = decode(found[2]);
    if (key.length !== 32 || encode(key) !== found[2]) throw new Error("invalid");
    return {room: found[1], key};
  }

  function newInvitation() {
    const room = Array.from(crypto.getRandomValues(new Uint8Array(16)), n => n.toString(16).padStart(2, "0")).join("");
    return {room, key: crypto.getRandomValues(new Uint8Array(32))};
  }

  let initialInvite = stripInvitation();
  const opener = document.getElementById("open-secret-room");

  function openWindow(prefill) {
    if (currentWindow) currentWindow.close();
    const dialog = document.createElement("dialog");
    dialog.id = "secret-room";
    dialog.setAttribute("aria-labelledby", "room-title");
    // Only fixed interface markup goes here. Invitations and messages never enter HTML.
    dialog.innerHTML = `
      <div class="room-shell">
        <header class="room-bar">
          <span class="room-emblem" aria-hidden="true"></span>
          <h2 id="room-title">Sala da Cachoeira</h2>
          <span id="room-status" role="status">Sem conta</span>
          <button type="button" id="room-close" aria-label="Fechar sala">Fechar</button>
        </header>
        <div class="room-scene">
          <div class="room-art" aria-hidden="true"></div>
          <section class="room-card" aria-labelledby="room-heading">
            <h3 id="room-heading" tabindex="-1"></h3>
            <p id="room-description"></p>
            <div id="room-lobby" class="room-actions">
              <button type="button" class="room-primary" id="room-create">Criar sala</button>
              <button type="button" class="room-secondary" id="room-join">Entrar com convite</button>
            </div>
            <form id="room-join-form" method="dialog" hidden>
              <label for="room-invite">Código ou link do convite</label>
              <input id="room-invite" type="password" placeholder="Cole o convite aqui" autocomplete="off"
                     autocapitalize="none" spellcheck="false" maxlength="2048" required>
              <div class="room-actions">
                <button id="room-enter" class="room-primary" type="submit">Entrar na sala</button>
                <button id="room-back" class="room-secondary" type="button">Voltar</button>
              </div>
            </form>
            <div id="room-share" class="room-actions" hidden>
              <button type="button" class="room-primary" id="room-copy-link">Copiar link</button>
              <button type="button" class="room-secondary" id="room-copy-code">Copiar código para o aplicativo</button>
              <label for="room-manual-copy" id="room-manual-label" hidden>Convite para copiar</label>
              <input id="room-manual-copy" type="text" readonly autocomplete="off" hidden>
            </div>
            <p id="room-notice" role="status"></p>
          </section>
          <ol id="room-messages" role="log" aria-label="Mensagens da sessão" aria-live="polite" aria-relevant="additions" tabindex="0" hidden></ol>
        </div>
        <form id="room-compose" method="dialog" hidden>
          <label class="sr-only" for="room-text">Mensagem privada</label>
          <div class="room-write"><textarea id="room-text" rows="2" placeholder="Escreva uma mensagem privada…" aria-describedby="room-count"></textarea>
          <button type="submit" id="room-send" class="room-primary">Enviar</button></div>
          <div class="room-compose-meta"><span>Enter envia · Shift + Enter quebra a linha</span><span id="room-count"></span></div>
        </form>
        <footer class="room-foot"><div><strong id="room-policy">Sessão temporária</strong>
          <span>O servidor pode ver seu IP e os horários da conexão.</span>
          <p id="room-invite-note" role="status" hidden></p></div>
          <button id="room-leave" class="room-secondary" type="button" hidden>Encerrar sala</button>
        </footer>
      </div>`;
    const get = id => dialog.querySelector("#room-" + id);
    const card = dialog.querySelector(".room-card");
    const feed = get("messages");
    const editor = get("text");
    let active = null;
    const reach = {
      lobby: ["Sem conta", "Conversa privada", "Crie uma sala para duas pessoas com criptografia de ponta a ponta."],
      join: ["Sem conta", "Entrar com convite", "Use o convite de quem criou a sala neste servidor."],
      opening: ["Conectando…", "Abrindo a sala…", "Aguarde a conexão com o servidor."],
      waiting: ["1 de 2 na sala", "Convide alguém", "Envie o convite por um canal de confiança. Quem o receber poderá ocupar a segunda vaga."],
      handshake: ["Autenticando…", "Conferindo o convite", "Os dispositivos estão estabelecendo a conexão criptografada."],
      chat: ["2 de 2 na sala", "", ""]
    };

    function display(stage, note = "") {
      const [status, title, description] = reach[stage];
      dialog.dataset.stage = stage;
      get("status").textContent = status;
      get("heading").textContent = title;
      get("description").textContent = description;
      get("lobby").hidden = stage !== "lobby";
      get("join-form").hidden = stage !== "join";
      get("share").hidden = stage !== "waiting";
      card.hidden = stage === "chat";
      feed.hidden = stage !== "chat";
      get("compose").hidden = stage !== "chat";
      get("policy").textContent = stage === "chat" ? "Criptografia de ponta a ponta" : "Sessão temporária";
      get("leave").hidden = !active;
      get("notice").textContent = note;
      if (!active) {
        get("invite-note").textContent = "";
        get("invite-note").hidden = true;
      }
      get("create").disabled = get("join").disabled = !secure;
      if (!secure) get("notice").textContent = "Abra o site por HTTPS para usar a sala privada.";
    }

    function cleanup() {
      const previous = active;
      active = null; // Invalidate network and asynchronous engine callbacks before closing.
      if (previous) {
        clearTimeout(previous.timer);
        previous.key.fill(0);
        previous.engine?.close();
        previous.engine = null;
        const ws = previous.socket;
        if (ws && ws.readyState < WebSocket.CLOSING) ws.close();
      }
      get("invite").value = get("manual-copy").value = editor.value = "";
      get("manual-label").hidden = get("manual-copy").hidden = true;
      get("count").textContent = "";
      feed.replaceChildren();
    }

    function end(s, reason) {
      if (s !== active) return;
      cleanup();
      display("lobby", reason);
      get("create").focus();
    }

    function timeout(s, ms, reason) {
      clearTimeout(s.timer);
      s.timer = setTimeout(() => end(s, reason), ms);
    }

    function sendOutput(s) {
      if (active !== s || s.socket.readyState !== WebSocket.OPEN || s.socket.bufferedAmount > 65536) throw new Error("socket");
      const data = s.engine.takeOutput();
      // A WebSocket envelope (including base64) is capped at 16 KiB.
      const parts = Math.ceil(data.length / 12280);
      for (let i = 0; i < parts; i++) {
        const start = Math.floor(i * data.length / parts);
        const end = Math.floor((i + 1) * data.length / parts);
        s.socket.send("DATA." + encode(data.subarray(start, end)));
      }
    }

    function addLine(text, own) {
      const follow = own || feed.scrollHeight - feed.clientHeight - feed.scrollTop < 48;
      const row = document.createElement("li");
      row.className = own ? "room-line room-own" : "room-line";
      const bubble = document.createElement("div");
      const meta = document.createElement("small");
      const stamp = document.createElement("time");
      const now = new Date();
      stamp.dateTime = now.toISOString();
      stamp.textContent = clock.format(now);
      meta.append(own ? "Você · " : "Visitante · ", stamp);
      const body = document.createElement("p");
      body.textContent = text;
      bubble.append(meta, body);
      row.append(bubble);
      if (feed.children.length >= 500) feed.firstElementChild.remove();
      feed.append(row);
      if (follow) feed.scrollTop = feed.scrollHeight;
    }

    async function receive(s, frame) {
      if (active !== s) return;
      if (frame === "WAIT" && s.host && s.stage === "opening") {
        clearTimeout(s.timer);
        s.stage = "waiting";
        display(s.stage);
        get("copy-link").focus();
        return;
      }
      if (frame === "READY" && (s.stage === "waiting" || s.stage === "opening")) {
        s.stage = "handshake";
        get("manual-copy").value = "";
        display(s.stage);
        timeout(s, 20000, "A conexão criptografada demorou para responder. Crie outro convite.");
        const engine = await s.module.createSecretEngine({host: s.host, room: s.room, key: s.key});
        if (active !== s) { engine.close(); return; }
        s.key.fill(0);
        s.engine = engine;
      } else if (s.engine && /^DATA\.[A-Za-z0-9_-]{22,}$/.test(frame)) {
        s.engine.receive(decode(frame.slice(5)));
      } else {
        throw new Error("unexpected frame");
      }
      sendOutput(s);
      if (s.stage === "handshake" && s.engine.isReady()) {
        s.stage = "chat";
        clearTimeout(s.timer);
        display(s.stage);
        updateComposer();
        editor.focus();
      }
      if (s.stage === "chat") {
        const messages = s.engine.takeMessages();
        const now = performance.now();
        if (now - s.receiveStart >= 1000) { s.receiveStart = now; s.received = 0; }
        s.received += messages.length;
        if (s.received > 32) throw new Error("message rate");
        for (const text of messages) addLine(text, false);
      }
    }

    async function connect(host) {
      if (!secure || active) return;
      let material;
      try { material = host ? newInvitation() : parseInvite(get("invite").value); }
      catch (error) {
        get("notice").textContent = error.message === "server" ? "Use o convite no servidor em que a sala foi criada." : "Cole um código WC1 ou link de convite válido.";
        get("invite").focus();
        return;
      }
      const s = {host, ...material, socket: null, engine: null, stage: "opening", receiveStart: performance.now(), received: 0};
      active = s;
      get("invite").value = "";
      display(s.stage);
      get("leave").focus();
      timeout(s, 90000, "O servidor demorou para responder. Abra uma nova sala.");
      try {
        s.module = await import("./secret-engine.js");
        if (active !== s) return;
        const endpoint = new URL("/secret/" + s.room + (host ? "/host" : "/guest"), location.origin);
        endpoint.protocol = location.protocol === "https:" ? "wss:" : "ws:";
        const ws = new WebSocket(endpoint);
        s.socket = ws;
        // READY may trigger asynchronous WASM initialization. Process following records in wire order.
        let sequence = Promise.resolve();
        let queued = 0;
        ws.addEventListener("message", ({data}) => {
          if (active !== s) return;
          if (typeof data !== "string" || data.length > 16384 || ++queued > 48) {
            end(s, "A conexão enviou dados inválidos. Crie um novo convite.");
            return;
          }
          sequence = sequence.then(() => receive(s, data))
            .catch(() => end(s, "Não foi possível autenticar a conversa. Crie um novo convite."))
            .finally(() => queued--);
        });
        ws.addEventListener("close", () => end(s, s.stage === "chat" ? "A conversa foi encerrada." :
          "Sala indisponível. O convite pode ter expirado ou a sala já está ocupada."));
        ws.addEventListener("error", () => end(s, "Não foi possível conectar à sala. Confira sua conexão e o servidor."));
      } catch { end(s, "Não foi possível iniciar a sala criptografada neste navegador."); }
    }

    async function share(link) {
      const s = active;
      if (!s || !s.host || s.stage !== "waiting") return;
      const code = "WC1." + s.room + "." + encode(s.key);
      const value = link ? location.origin + "/#room=" + code : code;
      try {
        await navigator.clipboard.writeText(value);
        if (s === active && s.stage === "waiting") get("notice").textContent = link ? "Link copiado." : "Código copiado.";
      } catch {
        if (s !== active || s.stage !== "waiting") return;
        const field = get("manual-copy");
        field.hidden = get("manual-label").hidden = false;
        field.value = value;
        field.focus();
        field.select();
        get("notice").textContent = "Copie o convite selecionado.";
      }
    }

    function updateComposer() {
      const count = Array.from(editor.value).length;
      get("count").textContent = count + " / 2000";
      const valid = editor.value.trim() && count <= 2000 && !/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/u.test(editor.value);
      get("send").disabled = active?.stage !== "chat" || !valid;
    }

    get("create").addEventListener("click", () => connect(true));
    function showJoin(code) {
      if (!secure) return;
      if (active) {
        const note = get("invite-note");
        note.hidden = false;
        note.textContent = "Encerre a sala atual e abra o link do novo convite novamente.";
        return;
      }
      display("join");
      get("invite").value = code.slice(0, 2048);
      (code ? get("enter") : get("invite")).focus();
    }
    get("join").addEventListener("click", () => showJoin(""));
    get("back").addEventListener("click", () => { get("invite").value = ""; display("lobby"); get("join").focus(); });
    get("copy-link").addEventListener("click", () => share(true));
    get("copy-code").addEventListener("click", () => share(false));
    get("join-form").addEventListener("submit", event => { event.preventDefault(); connect(false); });
    get("compose").addEventListener("submit", event => {
      event.preventDefault();
      const s = active;
      if (s?.stage !== "chat" || get("send").disabled) return;
      try {
        const text = editor.value;
        s.engine.send(text);
        sendOutput(s);
        addLine(text, true);
        editor.value = "";
        updateComposer();
        editor.focus();
      } catch { end(s, "A conexão foi interrompida. A entrega da mensagem ficou sem confirmação."); }
    });
    editor.addEventListener("input", updateComposer);
    editor.addEventListener("keydown", event => {
      if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
        event.preventDefault();
        if (!get("send").disabled) get("compose").requestSubmit();
      }
    });
    const pause = () => dialog.classList.toggle("room-paused", document.hidden);
    document.addEventListener("visibilitychange", pause);
    const dismiss = () => {
      if (!dialog.isConnected) return;
      cleanup();
      document.removeEventListener("visibilitychange", pause);
      dialog.close();
      dialog.remove();
      currentWindow = null;
      if (!document.hidden) opener.focus();
    };
    get("leave").addEventListener("click", () => { if (active) end(active, "Sala encerrada."); });
    get("close").addEventListener("click", dismiss);
    dialog.addEventListener("cancel", event => { event.preventDefault(); dismiss(); });
    currentWindow = {close: dismiss, showJoin};
    document.body.append(dialog);
    display("lobby");
    dialog.showModal();
    if (prefill !== null && secure) showJoin(prefill);
    else if (secure) get("create").focus();
    else get("close").focus();
  }

  opener.addEventListener("click", () => openWindow(null));
  window.addEventListener("pagehide", () => currentWindow?.close());
  window.addEventListener("hashchange", () => {
    const invite = stripInvitation();
    if (invite === null) return;
    if (currentWindow) currentWindow.showJoin(invite);
    else openWindow(invite);
  });
  if (initialInvite !== null) {
    openWindow(initialInvite);
    initialInvite = null;
  }
})();
