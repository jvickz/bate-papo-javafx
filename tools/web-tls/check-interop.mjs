import assert from "node:assert/strict";
import fs from "node:fs/promises";
import { spawn, execFileSync } from "node:child_process";
import { createInterface } from "node:readline";
import path from "node:path";
import os from "node:os";
import { fileURLToPath } from "node:url";
import { createSecretEngine } from "../../server/src/main/resources/web/secret-engine.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const app = path.resolve(here, "../../Wavecove/app");
const packaged = [path.join(app, "client-1.0.0.jar"), path.join(app, "lib", "*")].join(path.delimiter);
const classes = await fs.mkdtemp(path.join(os.tmpdir(), "wavecove-interop-"));
const java = name => process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin", name) : name;
const wasm = await fs.readFile(new URL("../../server/src/main/resources/web/secret-engine.wasm", import.meta.url));
globalThis.fetch = async url => {
    assert.equal(path.basename(url.pathname), "secret-engine.wasm");
    return new Response(wasm);
};

async function checkNativePeer(host) {
    const room = Buffer.from(crypto.getRandomValues(new Uint8Array(16))).toString("hex");
    const key = crypto.getRandomValues(new Uint8Array(32));
    const engine = await createSecretEngine({host, room, key});
    const child = spawn(java("java"), ["-cp", [classes, packaged].join(path.delimiter),
        "br.com.batepapo.client.InteropPeer", host ? "guest" : "host", room],
        {stdio: ["pipe", "pipe", "inherit"], windowsHide: true});
    const lines = createInterface({input: child.stdout})[Symbol.asyncIterator]();
    const exit = new Promise((resolve, reject) => {
        child.once("error", reject);
        child.once("exit", resolve);
    });
    const timeout = setTimeout(() => child.kill(), 25000);
    try {
        child.stdin.write(Buffer.from(key).toString("base64") + "\n");
        let sent = false, delivered = false, nativeText = [];
        const payload = "🌿".repeat(2000);
        for (let round = 0; round < 12 && !(delivered && nativeText.length); ++round) {
            child.stdin.write(Buffer.from(engine.takeOutput()).toString("base64") + "\n");
            const line = await lines.next();
            assert.equal(line.done, false, "Native peer quit during handshake");
            const [wire, received] = line.value.split("|");
            if (wire) engine.receive(Uint8Array.from(Buffer.from(wire, "base64")));
            nativeText.push(...engine.takeMessages());
            if (received) {
                assert.equal(Buffer.from(received, "base64").toString(), payload);
                delivered = true;
            }
            if (engine.isReady() && !sent) { engine.send(payload); sent = true; }
        }
        assert.equal(delivered, true);
        assert.deepEqual(nativeText, ["Do Windows 🌿"]);
    } finally {
        engine.close();
        child.stdin.end();
        const status = await exit;
        clearTimeout(timeout);
        assert.equal(status, 0);
    }
}

async function checkRecordAuthentication() {
    const room = "bc".repeat(16);
    const key = crypto.getRandomValues(new Uint8Array(32));
    const host = await createSecretEngine({room, key, host: true});
    const guest = await createSecretEngine({room, key, host: false});
    const wrongHost = await createSecretEngine({room, key: crypto.getRandomValues(new Uint8Array(32)), host: true});
    try {
        const hello = guest.takeOutput();
        assert.throws(() => wrongHost.receive(hello));
        host.receive(hello);
        guest.receive(host.takeOutput());
        host.receive(guest.takeOutput());
        assert(host.isReady() && guest.isReady());
        const text = "Uma mensagem autenticada.";
        guest.send(text);
        const wire = guest.takeOutput();
        assert.equal(Buffer.from(wire).includes(Buffer.from(text)), false);
        host.receive(wire.subarray(0, 2));
        host.receive(wire.subarray(2));
        assert.deepEqual(host.takeMessages(), [text]);
        assert.throws(() => host.receive(wire));
        assert.equal(host.isReady(), false);
    } finally { guest.close(); host.close(); wrongHost.close(); }
}

try {
    execFileSync(java("javac"), ["-encoding", "UTF-8", "-cp", packaged,
        "-d", classes, path.join(here, "InteropPeer.java")]);
    await checkNativePeer(true);
    await checkNativePeer(false);
    await checkRecordAuthentication();
    console.log("Browser host and guest exchanged authenticated messages with the packaged Windows client.");
} finally {
    await fs.rm(classes, {recursive: true, force: true});
}
