const encoder = new TextEncoder();
const decoder = new TextDecoder("utf-8", {fatal: true, ignoreBOM: true});
const maxTextBytes = 8000;
const maxWireBytes = Math.floor((16384 - 5) * 3 / 4);
const failText = "Não foi possível autenticar a conversa.";
let compiled;

async function loadModule() {
    if (!compiled) {
        compiled = (async () => {
            const response = await fetch(new URL("./secret-engine.wasm", import.meta.url), {
                credentials: "omit", redirect: "error"
            });
            if (!response.ok) throw new Error("Não foi possível carregar a criptografia.");
            return WebAssembly.compile(await response.arrayBuffer());
        })().catch(error => {
            compiled = undefined;
            throw error;
        });
    }
    return compiled;
}

function validateText(text) {
    if (typeof text !== "string" || !text.trim() || [...text].length > 2000 ||
        /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/u.test(text)) {
        throw new Error("Escreva de 1 a 2000 caracteres.");
    }
}

/** One isolated Mbed TLS instance per conversation; no persistent client state. */
export async function createSecretEngine({host, room, key}) {
    if (typeof host !== "boolean" || !/^[a-f0-9]{32}$/u.test(room) ||
        !(key instanceof Uint8Array) || key.length !== 32) throw new Error("Convite inválido.");
    let tls, closed = false, pending = new Uint8Array(), messages = [];
    const instance = await WebAssembly.instantiate(await loadModule(), {
        browser: {random(pointer, length) {
            const bytes = new Uint8Array(tls.memory.buffer, pointer, length);
            for (let offset = 0; offset < length; offset += 65536) {
                crypto.getRandomValues(bytes.subarray(offset, offset + 65536));
            }
        }}
    });
    tls = instance.exports;
    tls._initialize();
    const pointer = tls.wc_buffer();
    const scratch = length => new Uint8Array(tls.memory.buffer, pointer, length);

    function close() {
        if (closed) return;
        closed = true;
        try { tls.wc_free(); }
        finally {
            new Uint8Array(tls.memory.buffer).fill(0);
            pending.fill(0);
            pending = new Uint8Array();
            messages = [];
        }
    }
    function check(code) {
        if (code < 0) throw new Error(failText);
        return code;
    }
    function pump() {
        if (!tls.wc_ready()) check(tls.wc_handshake());
        if (!tls.wc_ready()) return;
        let length;
        while ((length = check(tls.wc_read())) > 0) {
            const bytes = new Uint8Array(pending.length + length);
            bytes.set(pending);
            bytes.set(scratch(length), pending.length);
            scratch(length).fill(0);
            pending.fill(0);
            let offset = 0;
            try {
                while (offset + 2 <= bytes.length) {
                    const size = bytes[offset] * 256 + bytes[offset + 1];
                    if (!size || size > maxTextBytes) throw new Error(failText);
                    if (offset + size + 2 > bytes.length) break;
                    const text = decoder.decode(bytes.subarray(offset + 2, offset + 2 + size));
                    validateText(text);
                    if (messages.length >= 64) throw new Error("Excesso de mensagens.");
                    messages.push(text);
                    offset += size + 2;
                }
                pending = bytes.slice(offset);
            } finally { bytes.fill(0); }
        }
    }
    try {
        const identity = encoder.encode("wavecove/secret/v1/" + room);
        scratch(32).set(key);
        scratch(32 + identity.length).set(identity, 32);
        check(tls.wc_start(host ? 1 : 0, identity.length));
        pump();
    } catch (error) { close(); throw error; }

    return {
        isReady() { return !closed && Boolean(tls.wc_ready()); },
        receive(wire) {
            if (closed) throw new Error("Sala encerrada.");
            try {
                if (!(wire instanceof Uint8Array) || !wire.length || wire.length > maxWireBytes)
                    throw new Error("Registro inválido.");
                scratch(wire.length).set(wire);
                check(tls.wc_feed(wire.length));
                pump();
            } catch (error) { close(); throw error; }
        },
        send(text) {
            if (closed || !tls.wc_ready()) throw new Error("Aguarde a conexão.");
            validateText(text);
            const bytes = encoder.encode(text);
            try {
                const frame = scratch(bytes.length + 2);
                frame[0] = bytes.length >>> 8;
                frame[1] = bytes.length & 255;
                frame.set(bytes, 2);
                check(tls.wc_write(frame.length));
            } catch (error) { close(); throw error; }
            finally { bytes.fill(0); }
        },
        takeMessages() {
            const batch = messages;
            messages = [];
            return batch;
        },
        takeOutput() {
            if (closed) return new Uint8Array();
            const size = tls.wc_output();
            return scratch(size).slice();
        },
        close
    };
}
