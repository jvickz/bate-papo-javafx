// Match the desktop harbor at twilight on login and first light on registration.
(() => {
  "use strict";
  const auth = document.getElementById("auth-view");
  const canvas = document.getElementById("registration-harbor");
  const context = canvas.getContext("2d", {alpha: false});
  const reduced = matchMedia("(prefers-reduced-motion: reduce)");
  const palettes = {
    login: {
      sky: [35, 51, 77], horizonColor: [183, 149, 164], sea: [48, 79, 99],
      foam: [148, 185, 180], sun: [242, 206, 155], sunHeight: 22, ridge: "#586278"
    },
    register: {
      sky: [96, 141, 155], horizonColor: [255, 208, 158], sea: [57, 113, 121],
      foam: [193, 217, 190], sun: [255, 240, 191], sunHeight: 9, ridge: "#899993"
    }
  };
  const shade = [34, 52, 66];
  const rock = "#354657", iron = "#344453", brick = "#b65e50", roof = "#78434a";
  const wall = "#e6d4ba", wallShade = "#aeb0a5", light = "#fff3bc";
  let frame = 0, lastFrame = null, phase = 0, mode = "login";

  function blend(a, b, t) {
    return "rgb(" + a.map((value, i) => Math.trunc(value + (b[i] - value) * t)).join(",") + ")";
  }

  function rect(color, x, y, width, height) {
    context.fillStyle = color;
    context.fillRect(x, y, width, height);
  }

  function lighthouse(x, base) {
    const lantern = base - 39;
    const sweep = Math.sin(phase * .65);
    context.globalAlpha = .12 + .10 * Math.abs(sweep);
    context.fillStyle = light;
    context.beginPath();
    context.moveTo(x, lantern);
    context.lineTo(x + 52 * sweep, lantern - 4);
    context.lineTo(x + 52 * sweep, lantern + 3);
    context.closePath();
    context.fill();
    context.globalAlpha = 1;
    for (let row = 0; row < 34; row++) {
      const half = 3 + Math.floor(row / 12);
      const stripe = (row >= 8 && row < 14) || (row >= 23 && row < 29);
      rect(stripe ? brick : wall, x - half, base - 34 + row, half * 2 + 1, 1);
      rect(stripe ? roof : wallShade, x + half - 1, base - 34 + row, 2, 1);
    }
    rect(iron, x - 5, base - 35, 11, 2);
    rect(iron, x - 3, lantern - 2, 7, 6);
    rect(light, x - 2, lantern - 1, 2, 4);
    rect(light, x + 1, lantern - 1, 2, 4);
    rect(roof, x - 5, lantern - 3, 11, 1);
    rect(brick, x - 3, lantern - 5, 7, 2);
    rect(roof, x - 1, lantern - 6, 3, 1);
    rect(iron, x, lantern - 8, 1, 2);
    rect(iron, x - 1, base - 4, 3, 4);
    rect(iron, x, base - 17, 2, 3);
    rect(wallShade, x + 10, base - 5, 10, 6);
    rect(roof, x + 8, base - 6, 14, 2);
    rect(brick, x + 10, base - 8, 10, 2);
    rect(iron, x + 15, base - 3, 2, 4);
  }

  function draw() {
    const width = canvas.clientWidth, height = canvas.clientHeight;
    if (!context || !width || !height) return;
    if (canvas.width !== width) canvas.width = width;
    if (canvas.height !== height) canvas.height = height;
    const pixel = Math.max(2, Math.round(Math.min(width / 270, height / 170)));
    context.setTransform(pixel, 0, 0, pixel, 0, 0);
    const w = Math.ceil(width / pixel), h = Math.ceil(height / pixel);
    const horizon = Math.round(h * .55);
    const {sky, horizonColor, sea, foam, sun, sunHeight, ridge} = palettes[mode];
    for (let y = 0; y < horizon; y += 3) {
      rect(blend(sky, horizonColor, y / horizon), 0, y, w, 3);
    }
    const sunX = Math.trunc(w * .66), sunY = horizon - sunHeight;
    const sunColor = "rgb(" + sun.join(",") + ")";
    for (let dy = -10; dy <= 10; dy++) {
      const radius = Math.trunc(Math.sqrt(100 - dy * dy));
      rect(sunColor, sunX - radius, sunY + dy, radius * 2 + 1, 1);
    }
    for (let i = 0; i < 7; i++) {
      const x = Math.trunc(i * 59 + phase * .5) % (w + 36) - 30;
      const y = 14 + (i * 17) % Math.max(16, horizon - 28);
      rect(blend(horizonColor, sky, .42), x, y, 20 + i, 1);
      rect(blend(horizonColor, sky, .32), x + 6, y - 1, 9 + i, 1);
    }
    for (let x = 0; x < w; x += 2) {
      const ridgeHeight = Math.trunc(5 + 3 * Math.sin(x * .071) + 2 * Math.cos(x * .18));
      rect(ridge, x, horizon - ridgeHeight, 2, ridgeHeight + 1);
    }
    for (let y = horizon; y < h; y += 3) {
      rect(blend(sea, shade, (y - horizon) / (h - horizon) * .85), 0, y, w, 3);
    }
    for (let i = 0; i < 82; i++) {
      const y = horizon + 2 + i * 23 % Math.max(1, h - horizon - 2);
      const x = Math.trunc(i * 67 % w + 2 * Math.sin(phase + i));
      rect(blend(sea, foam, .20 + i % 4 * .08), x, y, 2 + i % 7, 1);
    }
    for (let i = 0; i < 13; i++) {
      const x = sunX + Math.trunc(Math.sin(i * 2.5 + phase * .55) * (3 + i));
      rect(blend(sea, sun, .58 - i * .025), x - 3, horizon + i * 2 + 1, 4 + i % 7, 1);
    }
    const towerX = Math.round(w * .83), base = horizon + 4, shore = towerX - 13;
    for (let x = shore - 15; x < w; x += 2) {
      const dx = x - shore;
      const top = base + (dx < 0 ? Math.trunc(-dx / 2) + 2 : Math.trunc(2 + 1.5 * Math.sin(dx * .12)));
      const bottom = base + 10 + Math.min(8, Math.trunc((dx + 15) / 5)) + Math.trunc(1.7 * Math.sin(dx * .6));
      rect("#223442", x, top, 2, Math.max(1, bottom - top));
      rect(rock, x, top, 2, Math.max(1, Math.trunc((bottom - top) / 2)));
      rect(x % 8 < 4 ? "#657d70" : "#9baf87", x, top, 2, 1);
    }
    lighthouse(towerX, base);
    for (let i = 0; i < 3; i++) {
      const x = Math.trunc(w * .64) + i * 7 + Math.trunc(4 * Math.sin(phase * .23));
      const y = horizon - 38 + (i % 2) * 3;
      rect(rock, x, y, 2, 1);
      rect(rock, x + 2, y + 1, 1, 1);
      rect(rock, x + 3, y, 2, 1);
    }
  }

  function tick(now) {
    if (lastFrame === null || now - lastFrame >= 50) {
      phase += lastFrame === null ? 0 : Math.min(.1, (now - lastFrame) / 1000);
      lastFrame = now;
      draw();
    }
    frame = requestAnimationFrame(tick);
  }

  function update() {
    cancelAnimationFrame(frame);
    lastFrame = null;
    const nextMode = auth.dataset.mode === "register" ? "register" : "login";
    if (mode !== nextMode) phase = 0;
    mode = nextMode;
    if (!context || auth.hidden || document.hidden || document.querySelector("dialog[open]")) return;
    draw();
    if (!reduced.matches) frame = requestAnimationFrame(tick);
  }

  new MutationObserver(update).observe(auth, {attributes: true, attributeFilter: ["data-mode", "hidden"]});
  // The room removes its dialog before the closing toggle can reach the document.
  new MutationObserver(update).observe(document.body, {childList: true});
  new ResizeObserver(update).observe(canvas);
  reduced.addEventListener("change", update);
  document.addEventListener("visibilitychange", update);
  document.addEventListener("toggle", event => { if (event.target instanceof HTMLDialogElement) update(); }, true);
  update();

  document.getElementById("registration-origin").textContent = location.origin;
  const check = document.getElementById("registration-check");
  const status = document.getElementById("registration-health");
  check.addEventListener("click", async () => {
    check.disabled = true;
    status.textContent = "Verificando…";
    try {
      const result = await fetch("/health", {cache: "no-store", credentials: "omit", redirect: "error", signal: AbortSignal.timeout(15000)});
      const data = await result.json();
      status.textContent = result.ok && data?.status === "ok" ? "Servidor disponível." : "Servidor indisponível.";
    } catch {
      status.textContent = "Não foi possível verificar a conexão.";
    } finally {
      check.disabled = false;
    }
  });
})();
