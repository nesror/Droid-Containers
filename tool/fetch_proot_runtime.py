#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fetch_proot_runtime.py — proot 运行时分发预处理（方案 §3.1）

从 Termux 仓库下载 proot 及其依赖的 .deb（arm64 / x86_64），提取二进制：
  usr/bin/proot               -> libproot.so
  usr/lib/proot/loader        -> libproot_loader.so
  usr/lib/libtalloc.so.2      -> libtalloc.so（ELF .dynstr 原位改名，见下）
  libandroid-shmem            -> libandroid-shmem.so（proot 的 DT_NEEDED 硬依赖，缺了无法链接）

关键处理：
1. ELF 架构校验：按 e_machine 校验每个二进制（aarch64=183 / x86_64=62），
   防止错误架构产物入库；
2. ELF 动态依赖改名：proot 的 DT_NEEDED 为 libtalloc.so.2，jniLibs 只认 .so 结尾——
   在文件中原位查找 b"libtalloc.so.2\\x00" 改写为 b"libtalloc.so\\x00\\x00"
   （新串更短，NUL 补齐，字符串偏移不变）；
3. 产物落盘 core/engine/src/main/jniLibs/<abi>/lib*.so（二进制不入库，
   本脚本产物应加入 .gitignore）。

用法（版本号以 termux-main 仓库当前为准，可用参数覆盖）：
  python tool/fetch_proot_runtime.py \
      --proot-deb aarch64=<url> x86_64=<url> \
      --talloc-deb aarch64=<url> x86_64=<url>
"""
import argparse
import io
import shutil
import struct
import sys
import tarfile
import urllib.request
from pathlib import Path

EM_AARCH64 = 183
EM_X86_64 = 62

TERMUX_POOL = "https://packages.termux.dev/apt/termux-main/pool/main"

# 默认版本：以 termux-main Packages 索引为准（升级时核对 dists/stable/main/binary-*/Packages）
DEFAULT_PROOT_VERSION = "5.1.107.92"
DEFAULT_TALLOC_VERSION = "2.4.3"
DEFAULT_SHMEM_VERSION = "0.7"

DEFAULT_URLS = {
    "aarch64": {
        "proot": f"{TERMUX_POOL}/proot/proot_{DEFAULT_PROOT_VERSION}_aarch64.deb",
        "talloc": f"{TERMUX_POOL}/libt/libtalloc/libtalloc_{DEFAULT_TALLOC_VERSION}_aarch64.deb",
        "shmem": f"{TERMUX_POOL}/liba/libandroid-shmem/libandroid-shmem_{DEFAULT_SHMEM_VERSION}_aarch64.deb",
    },
    "x86_64": {
        "proot": f"{TERMUX_POOL}/proot/proot_{DEFAULT_PROOT_VERSION}_x86_64.deb",
        "talloc": f"{TERMUX_POOL}/libt/libtalloc/libtalloc_{DEFAULT_TALLOC_VERSION}_x86_64.deb",
        "shmem": f"{TERMUX_POOL}/liba/libandroid-shmem/libandroid-shmem_{DEFAULT_SHMEM_VERSION}_x86_64.deb",
    },
}

ABI_BY_EMACHINE = {EM_AARCH64: "aarch64", EM_X86_64: "x86_64"}

# 脚本内部键 -> Android jniLibs 目录名
JNI_DIR = {"aarch64": "arm64-v8a", "x86_64": "x86_64"}


def e_machine(data: bytes) -> int:
    """ELF 头 e_machine 字段（e_ident 16 字节 + e_type 2 字节 → 偏移 18，小端 2 字节）。"""
    if len(data) < 20 or data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    return struct.unpack_from("<H", data, 18)[0]


def check_arch(data: bytes, want_abi: str):
    em = e_machine(data)
    got = ABI_BY_EMACHINE.get(em)
    if got != want_abi:
        raise ValueError(f"arch mismatch: e_machine={em} ({got}), expected {want_abi}")


def patch_dynstr_libtalloc(data: bytes) -> bytes:
    """原位改写 libtalloc.so.2\\0 -> libtalloc.so\\0\\0（偏移不变）。"""
    old = b"libtalloc.so.2\x00"      # 14 字节
    new = b"libtalloc.so\x00\x00\x00"  # 14 字节（11 字符 + 3 个 NUL 补齐）
    assert len(old) == len(new)
    idx = data.find(old)
    if idx == -1:
        return data  # 无需改写（可能已处理或动态依赖不存在）
    return data[:idx] + new + data[idx + len(old):]


def download(url: str) -> bytes:
    print(f"GET {url}")
    # termux CDN 对 UA 敏感：Python 默认 UA 与长浏览器 UA 都会被 404，
    # 实测 "Mozilla/5.0" 精确串可通过
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        return resp.read()


def extract_deb(deb: bytes) -> dict:
    """返回 {成员路径: 内容}（含 data.tar.* 内的文件）。"""
    result = {}
    # .deb = ar 归档：global header + debian-binary + control.tar.* + data.tar.*
    stream = io.BytesIO(deb)
    magic = stream.read(8)
    if magic != b"!<arch>\n":
        raise ValueError("not a .deb (ar) file")
    while True:
        header = stream.read(60)
        if len(header) < 60:
            break
        name = header[0:16].decode("ascii").strip()
        size = int(header[48:58].decode("ascii").strip())
        payload = stream.read(size)
        if size % 2:
            stream.read(1)
        if name.startswith("data.tar"):
            with tarfile.open(fileobj=io.BytesIO(payload)) as tar:
                for member in tar.getmembers():
                    if member.isfile():
                        result["/" + member.name.lstrip("./")] = tar.extractfile(member).read()
    return result


def pick(files: dict, candidates):
    for cand in candidates:
        for path, data in files.items():
            if path.endswith(cand):
                return path, data
    return None, None


def process(abi: str, urls: dict, out_root: Path):
    proot_deb = extract_deb(download(urls["proot"]))
    talloc_deb = extract_deb(download(urls["talloc"]))
    process_from_debs(abi, proot_deb, talloc_deb, out_root)
    process_shmem(abi, urls["shmem"], out_root)


def process_from_debs(abi: str, proot_deb: dict, talloc_deb: dict, out_root: Path):
    out_dir = out_root / JNI_DIR[abi]
    out_dir.mkdir(parents=True, exist_ok=True)

    # 1) proot 主体：DT_NEEDED libtalloc.so.2 -> libtalloc.so（jniLibs 只认 .so 结尾）
    path, data = pick(proot_deb, ["usr/bin/proot"])
    if data is None:
        raise RuntimeError(f"proot binary not found in deb for {abi}")
    check_arch(data, abi)
    data = patch_dynstr_libtalloc(data)
    (out_dir / "libproot.so").write_bytes(data)
    print(f"  {abi}: libproot.so <- {path} ({len(data)} bytes, DT_NEEDED patched)")

    # 2) loader（64 位，与 proot 同架构）；loader32 为 32 位 guest 用（i386/ARM32），
    #    我们不支持 32 位 guest，跳过
    path, data = pick(proot_deb, ["usr/libexec/proot/loader32"])
    if data:
        try:
            check_arch(data, abi)
            (out_dir / "libproot_loader32.so").write_bytes(data)
            print(f"  {abi}: libproot_loader32.so <- {path} ({len(data)} bytes)")
        except ValueError:
            print(f"  {abi}: skip loader32 (arch {e_machine(data)}, 32-bit guests unsupported)")

    path, data = pick(proot_deb, ["usr/libexec/proot/loader", "usr/lib/proot/loader"])
    if data:
        check_arch(data, abi)
        (out_dir / "libproot_loader.so").write_bytes(data)
        print(f"  {abi}: libproot_loader.so <- {path} ({len(data)} bytes)")
    else:
        print(f"  {abi}: WARNING loader not found in proot deb")

    # 3) talloc 实体（termux 包只带完整版本号文件）重命名为 libtalloc.so
    path, data = pick(talloc_deb, ["libtalloc.so.2.4.3", "libtalloc.so.2", "libtalloc.so"])
    if data is None:
        raise RuntimeError(f"libtalloc not found in deb for {abi}")
    check_arch(data, abi)
    (out_dir / "libtalloc.so").write_bytes(data)
    print(f"  {abi}: libtalloc.so <- {path} ({len(data)} bytes)")


def process_shmem(abi: str, shmem_url: str, out_root: Path):
    """android-shmem（proot 的 DT_NEEDED 依赖，缺了会 CANNOT LINK EXECUTABLE）。"""
    out_dir = out_root / JNI_DIR[abi]
    out_dir.mkdir(parents=True, exist_ok=True)
    deb = extract_deb(download(shmem_url))
    path, data = pick(deb, ["libandroid-shmem.so"])
    if data is None:
        raise RuntimeError(f"libandroid-shmem.so not found in deb for {abi}")
    check_arch(data, abi)
    (out_dir / "libandroid-shmem.so").write_bytes(data)
    print(f"  {abi}: libandroid-shmem.so <- {path} ({len(data)} bytes)")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default=str(Path(__file__).resolve().parent.parent / "core/engine/src/main/jniLibs"))
    parser.add_argument("--proot-deb", nargs=2, action="append", metavar=("ABI", "URL"), default=[])
    parser.add_argument("--talloc-deb", nargs=2, action="append", metavar=("ABI", "URL"), default=[])
    parser.add_argument("--shmem-deb", nargs=2, action="append", metavar=("ABI", "URL"), default=[])
    parser.add_argument(
        "--from-local", metavar="DIR", default=None,
        help="skip network; read <DIR>/{proot,talloc,shmem}_<abi>.deb instead",
    )
    args = parser.parse_args()

    urls = {abi: dict(defaults) for abi, defaults in DEFAULT_URLS.items()}
    for abi, url in args.proot_deb:
        urls.setdefault(abi, {})["proot"] = url
    for abi, url in args.talloc_deb:
        urls.setdefault(abi, {})["talloc"] = url
    for abi, url in args.shmem_deb:
        urls.setdefault(abi, {})["shmem"] = url

    out_root = Path(args.out)
    local = Path(args.from_local) if args.from_local else None
    for abi in ("aarch64", "x86_64"):
        print(f"[{abi}]")
        if local:
            proot_deb = extract_deb((local / f"proot_{abi}.deb").read_bytes())
            talloc_deb = extract_deb((local / f"talloc_{abi}.deb").read_bytes())
            process_from_debs(abi, proot_deb, talloc_deb, out_root)
            shmem_deb = extract_deb((local / f"shmem_{abi}.deb").read_bytes())
            path, data = pick(shmem_deb, ["libandroid-shmem.so"])
            if data is None:
                raise RuntimeError(f"libandroid-shmem.so not found in local deb for {abi}")
            check_arch(data, abi)
            (out_root / JNI_DIR[abi] / "libandroid-shmem.so").write_bytes(data)
            print(f"  {abi}: libandroid-shmem.so <- {path} ({len(data)} bytes)")
        else:
            process(abi, urls[abi], out_root)
    print("done. Remember: jniLibs products are NOT committed; build.gradle packaging")
    print("must set useLegacyPackaging=true and keepDebugSymbols for **/libproot.so")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:  # noqa: BLE001
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(1)
