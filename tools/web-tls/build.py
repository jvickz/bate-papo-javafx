"""Build the browser's TLS module from the official Mbed TLS source release."""
import argparse
import hashlib
import pathlib
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
VERSION = "3.6.8"
PACKAGE = f"mbedtls-{VERSION}"
URL = f"https://github.com/Mbed-TLS/mbedtls/releases/download/{PACKAGE}/{PACKAGE}.tar.bz2"
SHA256 = "bc54b818e87f49c0b3281dd85d24adaede0e63b4e822e3fbd49c005a38b9595b"
EXPORTS = ("wc_buffer", "wc_start", "wc_ready", "wc_handshake", "wc_feed",
           "wc_read", "wc_write", "wc_output", "wc_free")


def write_notices(destination, source, zig):
    zigroot = pathlib.Path(shutil.which(zig)).resolve().parent
    wasi = zigroot / "lib/libc/wasi"
    pieces = [
        (f"Mbed TLS {VERSION}. Copyright The Mbed TLS Contributors. Distributed under Apache-2.0.",
         source / "LICENSE"),
        ("Zig 0.14.1 runtime", zigroot / "LICENSE"),
        ("wasi-libc", wasi / "LICENSE"),
        ("wasi-libc Apache 2.0 with LLVM exceptions", wasi / "LICENSE-APACHE-LLVM"),
        ("wasi-libc Apache 2.0", wasi / "LICENSE-APACHE"),
        ("wasi-libc MIT. Includes emmalloc, Copyright 2018 The Emscripten Authors.",
         wasi / "LICENSE-MIT"),
        ("musl", wasi / "libc-top-half/musl/COPYRIGHT"),
        ("CloudABI libc", wasi / "libc-bottom-half/cloudlibc/LICENSE"),
    ]
    destination.write_text("\n\n".join(
        title + "\n\n" + file.read_text(encoding="utf-8") for title, file in pieces
    ) + "\n", encoding="utf-8", newline="\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--zig", default="zig", help="path to Zig 0.14.1")
    parser.add_argument("--cache", type=pathlib.Path,
                        default=pathlib.Path(tempfile.gettempdir()) / "wavecove-web-tls")
    parser.add_argument("--out", type=pathlib.Path,
                        default=HERE.parents[1] / "server/src/main/resources/web")
    args = parser.parse_args()
    if subprocess.check_output([args.zig, "version"], text=True).strip() != "0.14.1":
        parser.error("This build uses Zig 0.14.1.")
    cache = args.cache.resolve()
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / f"{PACKAGE}.tar.bz2"
    if not archive.exists():
        urllib.request.urlretrieve(URL, archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        raise ValueError("Mbed TLS release checksum mismatch")
    with tempfile.TemporaryDirectory(dir=cache) as directory:
        with tarfile.open(archive) as release:
            release.extractall(directory, filter="data")
        source = pathlib.Path(directory) / PACKAGE
        flags = ["cc", "-target", "wasm32-wasi", "-Os", "-ffunction-sections",
                 "-fdata-sections", "-fvisibility=hidden", "-mexec-model=reactor",
                 "-Wl,--no-entry", "-Wl,--gc-sections", "-Wl,--strip-all",
                 "-Wl,--export-memory", "-Wl,-z,stack-size=98304",
                 "-Wl,--initial-memory=1048576", "-Wl,--max-memory=2097152",
                 '-DMBEDTLS_CONFIG_FILE="wavecove_tls.h"',
                 '-DMBEDTLS_PSA_CRYPTO_CONFIG_FILE="wavecove_psa.h"',
                 "-I", str(HERE), "-Iinclude", "-Ilibrary"]
        flags += [f"-Wl,--export={name}" for name in EXPORTS]
        sources = sorted(str(p.relative_to(source)) for p in (source / "library").glob("*.c"))
        subprocess.run([args.zig, *flags, str(HERE / "engine.c"), *sources,
                        "-o", "engine.wasm"], cwd=source, check=True)
        web = args.out.resolve()
        web.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / "engine.wasm", web / "secret-engine.wasm")
        write_notices(web / "secret-engine-license.txt", source, args.zig)


if __name__ == "__main__":
    main()
