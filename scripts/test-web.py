"""Exercise the packaged Wavecove web client locally with two real browser sessions.

Requires Python 3.10+, Playwright for Python, Java 21+, and Edge or Chromium.
Build the server jar first. Use --screenshots DIR to save the verified screens.
"""

import argparse
from contextlib import contextmanager
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
from urllib.parse import urlsplit

from playwright.sync_api import Page, expect, sync_playwright


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_JAR = ROOT / "server" / "target" / "server-1.0.0.jar"


def java_launcher():
    executable = "java.exe" if os.name == "nt" else "java"
    roots = []
    if os.environ.get("LOCALAPPDATA"):
        cache = Path(os.environ["LOCALAPPDATA"]) / "BatePapo" / "build-tools"
        roots.extend(sorted(cache.glob("jdk-21*"), reverse=True))
    if os.environ.get("JAVA_HOME"):
        roots.append(Path(os.environ["JAVA_HOME"]))
    for root in roots:
        candidate = root / "bin" / executable
        if candidate.is_file():
            return str(candidate)
    path = shutil.which(executable)
    if path:
        return path
    raise RuntimeError("Java 21 não encontrado. Configure JAVA_HOME ou execute o bootstrap do projeto.")


@contextmanager
def local_server(jar):
    if not jar.is_file():
        raise FileNotFoundError(f"Empacote o servidor antes de testar. JAR esperado em {jar}")
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as port_reservation:
        port_reservation.bind(("127.0.0.1", 0))
        port = port_reservation.getsockname()[1]
    with tempfile.TemporaryDirectory(prefix="wavecove-web-") as scratch:
        directory = Path(scratch)
        env = dict(os.environ)
        for name in ("DATABASE_URL", "RENDER", "TURN_KEY_ID", "TURN_API_TOKEN"):
            env.pop(name, None)
        env.update(PORT=str(port), WAVECOVE_BIND_ADDRESS="127.0.0.1",
                   WAVECOVE_ACCOUNT_FILE=str(directory / "accounts"))
        log_path = directory / "server.log"
        with log_path.open("wb") as log:
            process = subprocess.Popen([java_launcher(), "-Dfile.encoding=UTF-8", "-jar", str(jar.resolve())],
                                       cwd=directory, env=env, stdout=log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 90
                while time.monotonic() < deadline:
                    output = log_path.read_text(encoding="utf-8", errors="replace")
                    if process.poll() is not None:
                        raise RuntimeError(f"O servidor de teste encerrou na inicialização.\n{output[-3000:]}")
                    if f"Listening on http://127.0.0.1:{port}/" in output:
                        break
                    time.sleep(.2)
                else:
                    raise RuntimeError("O servidor de teste não confirmou a inicialização em 90 segundos.")
                yield f"http://127.0.0.1:{port}"
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)


def launch_browser(engine, explicit_path):
    if explicit_path:
        return engine.launch(executable_path=str(explicit_path.resolve()), headless=True)
    for env_var in ("PROGRAMFILES(X86)", "PROGRAMFILES", "LOCALAPPDATA"):
        base = os.getenv(env_var)
        if base:
            edge = Path(base) / "Microsoft" / "Edge" / "Application" / "msedge.exe"
            if edge.is_file():
                return engine.launch(executable_path=str(edge), headless=True)
    return engine.launch(headless=True)


def account(page, user, password, *, register=False):
    page.locator("#register-tab" if register else "#login-tab").click()
    page.locator("#auth-username").fill(user)
    page.locator("#auth-password").fill(password)
    page.locator("#auth-submit").click()
    expect(page.locator("#connection-status")).to_have_text("Conectado", timeout=45000)
    expect(page.locator("#self-name")).to_have_text(user)


def send_channel(page, text):
    page.locator("#message-input").fill(text)
    page.locator("#send-button").click()
    expect(page.locator("#messages .own").get_by_text(text, exact=True)).to_be_visible()
    expect(page.locator("#message-input")).to_have_value("")


def no_horizontal_overflow(page):
    width, scroll = page.evaluate("() => [innerWidth, document.documentElement.scrollWidth]")
    assert scroll <= width + 1, f"A página tem {scroll}px para uma janela de {width}px."


def visible_drawer(page, identifier):
    drawer = page.locator(identifier)
    if not drawer.evaluate("(details) => details.open"):
        drawer.locator(":scope > summary").click()


def capture(page, directory, name):
    if directory:
        directory.mkdir(parents=True, exist_ok=True)
        page.evaluate("() => document.fonts.ready")
        page.screenshot(path=str(directory / name), full_page=True, animations="disabled")


def bind_checks(context, url, errors):
    base = urlsplit(url)

    def allow_local(route):
        target = urlsplit(route.request.url)
        if target.scheme == base.scheme and target.netloc == base.netloc:
            route.continue_()
        else:
            errors.append(f"Requisição fora do servidor local: {target.hostname}")
            route.abort()

    def watch(page: Page):
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.on("response", lambda response: errors.append(f"HTTP {response.status} em {urlsplit(response.url).path}")
                if response.status >= 400 else None)
        page.on("requestfailed", lambda req: errors.append(f"{req.failure} em {urlsplit(req.url).path}")
                if req.failure not in ("net::ERR_ABORTED", "NS_BINDING_ABORTED") else None)
        page.on("websocket", lambda ws: errors.append("WebSocket fora do servidor local.")
                if urlsplit(ws.url).netloc != base.netloc else None)

    context.route("**/*", allow_local)
    context.on("page", watch)


def exercise(browser, url, screenshots):
    first = browser.new_context(viewport={"width": 1320, "height": 830}, locale="pt-BR")
    second = browser.new_context(viewport={"width": 390, "height": 844}, locale="pt-BR", has_touch=True)
    errors = []
    try:
        for context in (first, second):
            bind_checks(context, url, errors)
        a, b = first.new_page(), second.new_page()
        for page in (a, b):
            page.goto(url, wait_until="load")
            expect(page.locator("#auth-submit")).to_be_enabled()
            no_horizontal_overflow(page)
        capture(a, screenshots, "web-entrada.png")
        a.set_viewport_size({"width": 1080, "height": 760})
        a.bring_to_front()
        a.locator("#register-tab").click()
        harbor = a.locator("#registration-harbor")
        expect(harbor).to_be_visible()
        a.emulate_media(reduced_motion="no-preference")
        a.wait_for_function(
            "first => document.querySelector('#registration-harbor').toDataURL() !== first",
            arg=harbor.evaluate("canvas => canvas.toDataURL()"), timeout=6000)
        a.emulate_media(reduced_motion="reduce")
        a.wait_for_timeout(200)
        still = harbor.evaluate("canvas => canvas.toDataURL()")
        a.wait_for_timeout(350)
        assert still == harbor.evaluate("canvas => canvas.toDataURL()"), "Respeitar a redução de movimento."
        capture(a, screenshots, "web-cadastro-amanhecer.png")
        a.set_viewport_size({"width": 1320, "height": 830})
        a.emulate_media(reduced_motion="no-preference")
        password = secrets.token_urlsafe(25)
        account(a, "Marina", password, register=True)
        account(b, "Caio", password, register=True)
        for page in (a, b):
            expect(page.locator("#member-count")).to_have_text("2")
            expect(page.locator("#members li")).to_have_text(["Caio", "Marina"])
        hello = "Alguém já passou pela trilha da cachoeira?"
        reply = "Passei ontem. A vista lá de cima vale a caminhada!"
        send_channel(a, hello)
        expect(b.locator("#messages").get_by_text(hello, exact=True)).to_be_visible()
        send_channel(b, reply)
        expect(a.locator("#messages").get_by_text(reply, exact=True)).to_be_visible()

        a.locator("details.create-channel > summary").click()
        a.locator("#channel-name").fill("trilhas")
        a.locator("#channel-create").click()
        expect(a.locator("#channel-title")).to_have_text("#trilhas")
        expect(b.locator("#members li")).to_have_text(["Caio"])
        restricted = "Podemos combinar a caminhada por aqui."
        send_channel(a, restricted)
        general_only = "Vou deixar o convite no canal de trilhas."
        send_channel(b, general_only)
        expect(b.locator("#messages").get_by_text(restricted, exact=True)).to_have_count(0)
        expect(a.locator("#messages").get_by_text(general_only, exact=True)).to_have_count(0)
        visible_drawer(b, "#channel-drawer")
        b.locator('#channels button[data-channel="trilhas"]').click()
        expect(b.locator("#channel-title")).to_have_text("#trilhas")
        expect(a.locator("#member-count")).to_have_text("2")
        plan = "Conheço um mirante perto da queda. Vamos no sábado?"
        send_channel(b, plan)
        expect(a.locator("#messages").get_by_text(plan, exact=True)).to_be_visible()
        saturday = "Combinado! Levo o mapa e encontro vocês no início da trilha."
        send_channel(a, saturday)
        expect(b.locator("#messages").get_by_text(saturday, exact=True)).to_be_visible()
        if a.locator("details.create-channel").evaluate("(node) => node.open"):
            a.locator("details.create-channel > summary").click()
        no_horizontal_overflow(b)
        capture(a, screenshots, "web-canal.png")
        capture(b, screenshots, "web-canal-mobile.png")

        b.locator("#logout-button").click()
        expect(b.locator("#auth-view")).to_be_visible()
        expect(a.locator("#member-count")).to_have_text("1")
        a.locator("#open-dms").click()
        a.locator("#dm-peer").fill("Caio")
        a.locator(".dm-open-form button").click()
        private = "Caio, te mandei o ponto de encontro por aqui."
        a.locator("#dm-text").fill(private)
        a.locator(".dm-compose button").click()
        expect(a.locator(".dm-history").get_by_text(private, exact=True)).to_be_visible()
        account(b, "Caio", password)
        b.locator("#open-dms").click()
        b.locator('.dm-threads button[data-peer="Marina"]').click()
        expect(b.locator(".dm-history").get_by_text(private, exact=True)).to_be_visible()
        no_horizontal_overflow(b)
        answer = "Recebi! Até sábado."
        b.locator("#dm-text").fill(answer)
        b.locator(".dm-compose button").click()
        expect(b.locator(".dm-history").get_by_text(answer, exact=True)).to_be_visible()
        a.bring_to_front()
        expect(a.locator(".dm-history").get_by_text(answer, exact=True)).to_be_visible(timeout=25000)
        for page in (a, b):
            page.locator(".dm-close").click()
            expect(page.locator("#messages").get_by_text(private, exact=True)).to_have_count(0)
            page.locator("#logout-button").click()
            expect(page.locator("#auth-view")).to_be_visible()
            expect(page.locator("#messages li")).to_have_count(0)
        if errors:
            raise AssertionError("Falhas observadas no navegador:\n" + "\n".join(errors))
    finally:
        second.close()
        first.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    parser.add_argument("--browser-executable", type=Path)
    parser.add_argument("--screenshots", type=Path)
    parser.add_argument("--desktop-jar", type=Path, help="Testar também salas anônimas com o cliente Windows.")
    options = parser.parse_args()
    expect.set_options(timeout=15000)
    with local_server(options.jar) as url, sync_playwright() as engine:
        browser = launch_browser(engine.chromium, options.browser_executable)
        try:
            exercise(browser, url, options.screenshots)
            if options.desktop_jar:
                from room_interop import exercise_interop
                exercise_interop(browser, url, options.desktop_jar, java_launcher())
                print("OK: salas sem conta criadas no navegador e no Windows, mensagens nos dois sentidos e encerramento.")
        finally:
            browser.close()
    print("OK: cadastro, login, presença, canais isolados, envio bidirecional, DMs offline, logout e layout móvel.")


if __name__ == "__main__":
    main()
