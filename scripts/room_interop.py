"""Verify the installed desktop client against a browser that has never logged in."""

import base64
from contextlib import contextmanager
import os
from pathlib import Path
import queue
import subprocess
import tempfile
import threading
from urllib.parse import urlsplit

from playwright.sync_api import expect


@contextmanager
def desktop_peer(java, jar, classes, origin, role, invite=None):
    proc = subprocess.Popen(
        [java, "-Dfile.encoding=UTF-8", "-cp", os.pathsep.join([str(classes), str(jar)]),
         "br.com.batepapo.client.RoomDesktopPeer", origin, role],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        encoding="utf-8", errors="replace"
    )
    results = queue.Queue()

    def read_output():
        for line in proc.stdout:
            results.put(line.rstrip())
        results.put(None)

    threading.Thread(target=read_output, daemon=True).start()
    try:
        if invite:
            proc.stdin.write(invite + "\n")
            proc.stdin.flush()
        yield lambda: results.get(timeout=95)
        assert proc.wait(timeout=10) == 0, "O processo do cliente Windows terminou com erro."
    finally:
        if proc.poll() is None:
            proc.kill()
        proc.wait()
        proc.stdin.close()
        proc.stdout.close()


def exercise_interop(browser, origin, jar, java):
    jar = jar.resolve()
    if not jar.is_file():
        raise FileNotFoundError("É necessário o JAR do Wavecove com as bibliotecas da distribuição.")
    source = Path(__file__).parent / "test-support" / "RoomDesktopPeer.java"
    javac = Path(java).with_name("javac.exe" if os.name == "nt" else "javac")
    with tempfile.TemporaryDirectory(prefix="wavecove-peer-") as tmp:
        subprocess.run([str(javac), "-encoding", "UTF-8", "-cp", str(jar), "-d", tmp, str(source)], check=True)
        for browser_host in (True, False):
            context = browser.new_context(locale="pt-BR", viewport={"width": 920, "height": 720})
            # Exercise the actual copy action without writing the tester's system clipboard.
            context.add_init_script("""Object.defineProperty(navigator, 'clipboard', {
              value: {writeText: async value => { window.testCopy = value; } }
            });""")
            page = context.new_page()
            observed = []
            errors = []
            page.on("pageerror", lambda exc: errors.append(str(exc)))
            page.on("request", lambda req: observed.append(req.url))

            def watch(ws):
                observed.append(ws.url)
                ws.on("framesent", lambda record: observed.append(record))
                ws.on("framereceived", lambda record: observed.append(record))

            page.on("websocket", watch)
            try:
                invite = None
                if browser_host:
                    page.goto(origin, wait_until="domcontentloaded")
                    page.locator("#open-secret-room").click()
                    page.locator("#room-create").click()
                    page.locator("#room-copy-code").click(timeout=90000)
                    invite = page.evaluate("window.testCopy")
                    page.locator("#room-copy-link").click()
                    assert urlsplit(page.evaluate("window.testCopy")).fragment == "room=" + invite
                with desktop_peer(java, jar, tmp, origin, "guest" if browser_host else "host", invite) as event:
                    if not browser_host:
                        invite = event()
                        assert invite and invite.startswith("WC1."), "O Windows precisa gerar um convite."
                        page.goto(origin, wait_until="domcontentloaded")
                        page.locator("#open-secret-room").click()
                        page.locator("#room-join").click()
                        page.locator("#room-invite").fill(invite)
                        page.locator("#room-enter").click()
                    expect(page.locator("#room-messages")).to_contain_text("Mensagem do Windows 🌊", timeout=90000)
                    page.locator("#room-text").fill("Resposta do navegador 🌿")
                    page.locator("#room-send").click()
                    assert event() == "DELIVERED", "A resposta deve chegar ao Windows."
                    page.locator("#room-leave").click()
                    assert event() == "CLOSED", "A saída deve encerrar o par."
                assert all("/accounts/" not in s and "/direct/" not in s for s in observed)
                key = invite.split(".")[2]
                protected = [base64.urlsafe_b64decode(key + "="), "Mensagem do Windows 🌊".encode(),
                             "Resposta do navegador 🌿".encode()]
                for value in observed:
                    assert key not in value, "A chave não pode ser enviada ao servidor."
                    if value.startswith("DATA."):
                        raw = base64.urlsafe_b64decode(value[5:] + "===")
                        assert not any(secret in raw for secret in protected), "Vazamento de chave ou mensagem."
                assert not errors, "O navegador registrou um erro no fluxo privado."
            finally:
                context.close()
        with browser.new_context() as context:
            context.add_init_script("""Object.defineProperty(navigator, 'clipboard', {
              value: {writeText: async value => { window.testCopy = value; } }
            });""")
            page = context.new_page()
            page.goto(origin, wait_until="domcontentloaded")
            page.locator("#open-secret-room").click()
            page.locator("#room-create").click()
            page.locator("#room-copy-code").click(timeout=90000)
            prefix, room, _ = page.evaluate("window.testCopy").split(".")
            other_key = base64.urlsafe_b64encode(os.urandom(32)).decode().rstrip("=")
            with desktop_peer(java, jar, tmp, origin, "reject", f"{prefix}.{room}.{other_key}") as event:
                assert event() == "REJECTED", "Uma chave incorreta deve encerrar a negociação."
            expect(page.locator("#room-send")).not_to_be_visible()
