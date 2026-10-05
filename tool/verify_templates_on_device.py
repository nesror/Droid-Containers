#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""真机逐模板验证：模板库里的编排模板能否在手机上真正跑起来。

走 App 自身的远程控制 Web/API（`core:server` 的 `/api/v1`），对每个模板执行
「建项目 → 拉镜像 → up → 等容器 RUNNING → 端口探测 → 抓日志」，并把结果写成
JSON / JSONL，便于汇总成验证记录。

前置条件
--------
1. 手机已装本工程的 debug 包并处于运行状态；
2. 设置里已开启「Web 控制台」（`web_enabled=true`，默认端口 8765）；
3. 已把设备端口转发到本机，例如：
       adb forward tcp:18765 tcp:8765
4. 取到 Bearer token（存在设备 DataStore 里，可用下面命令读）：
       adb shell run-as cn.yzapp.androidcontainer cat files/datastore/settings.preferences_pb
   （protobuf 文本里 `api_token` 后面那串 32 位 hex 就是）也可在设置页重置后重新读。

用法
----
    python verify_templates_on_device.py --token <token> [--port 18765]
    python verify_templates_on_device.py --token <t> --only web-redis
    python verify_templates_on_device.py --token <t> --only smarthome --keep
    python verify_templates_on_device.py --list        # 只列模板与所需镜像

`--keep` 表示验证完不拆容器，便于留在手机上人工确认；默认验证完即 down + 删项目
（镜像保留，后续模板可复用，避免重复下载）。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

# ---------------------------------------------------------------- 常量

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
TEMPLATE_DIR = os.path.join(
    REPO_ROOT, "android-container-master", "core", "engine", "src", "main", "templates"
)
DEFAULT_OUT_DIR = os.path.join(REPO_ROOT, ".workbuddy", "tmp", "tpl-verify")

#: 模板展示顺序：先免费 / 已验证的轻量模板，再逐步到重型镜像；
#: 同一镜像在多个模板间复用（如 eclipse-mosquitto、nodered/node-red），
#: 按这个顺序跑可以少下一轮。
TEMPLATE_ORDER = [
    "web-redis",
    "python-lab",
    "mqtt-broker",
    "nodered",
    "smarthome",
    "filebrowser",
    "syncthing",
    "alist",
    "uptime-kuma",
    "vaultwarden",
    "mariadb-adminer",
    "n8n",
    "code-server",
]

#: HTTP 探测时被视为「服务已就绪」的状态码（401/403 也表示服务在正常应答）。
HTTP_OK_CODES = (200, 301, 302, 401, 403)


# ---------------------------------------------------------------- HTTP 辅助


class Api:
    def __init__(self, base: str, token: str):
        self.base = base.rstrip("/")
        self.token = token

    def call(self, method: str, path: str, body=None, timeout: int = 900):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Authorization", "Bearer " + self.token)
        if data:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.status, resp.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", "replace")

    def json(self, method: str, path: str, body=None, timeout: int = 900):
        code, raw = self.call(method, path, body, timeout)
        try:
            return code, json.loads(raw)
        except Exception:
            return code, raw


# ---------------------------------------------------------------- 模板解析


def template_path(tpl_id: str) -> str:
    return os.path.join(TEMPLATE_DIR, tpl_id + ".yaml")


def all_template_ids() -> list[str]:
    ids = []
    for name in os.listdir(TEMPLATE_DIR):
        if name.endswith(".yaml") and not name.startswith("_"):
            ids.append(name[: -len(".yaml")])
    ordered = [i for i in TEMPLATE_ORDER if i in ids]
    return ordered + sorted(i for i in ids if i not in ordered)


def compose_body(tpl_id: str) -> str:
    """抽出模板 YAML 里的 compose 正文（去掉块标量的公共缩进）。"""
    lines = open(template_path(tpl_id), encoding="utf-8").read().replace("\r\n", "\n").split("\n")
    idx = None
    for i, ln in enumerate(lines):
        if re.match(r"^compose:\s*\|", ln):
            idx = i
            break
    if idx is None:
        # 容错：整份文件就是 compose 正文
        return "\n".join(lines).strip("\n")
    body = lines[idx + 1:]
    while body and body[-1] == "":
        body.pop()
    indents = [len(l) - len(l.lstrip(" ")) for l in body if l.strip()]
    pad = min(indents) if indents else 0
    return "\n".join(l[pad:] if len(l) > pad else "" for l in body)


def images_of(yaml_text: str) -> list[str]:
    return sorted(set(re.findall(r"^\s+image:\s*(\S+)", yaml_text, re.M)))


def web_ports_of(tpl_id: str) -> list[int]:
    text = open(template_path(tpl_id), encoding="utf-8").read()
    m = re.search(r"webPorts:\s*\[([^\]]*)\]", text)
    if not m:
        return []
    return [int(x) for x in re.findall(r"\d+", m.group(1))]


def declared_ports(yaml_text: str) -> list[int]:
    return sorted({int(x) for x in re.findall(r'"(\d+):\d+"', yaml_text)})


# ---------------------------------------------------------------- 探测


def adb(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["adb", *args], capture_output=True, text=True)


def forward(device_port: int, host_port: int) -> None:
    """容器端口不重映射，从电脑探测需经 adb forward 打到设备同号端口。"""
    adb("forward", f"tcp:{host_port}", f"tcp:{device_port}")


def http_probe(device_port: int, attempts: int = 3, wait_s: int = 0):
    """经 adb forward 做 HTTP GET；wait_s>0 时轮询直到就绪或超时（首启慢的服务）。"""
    host_port = 20000 + device_port
    forward(device_port, host_port)
    last = "no attempt"
    deadline = time.time() + wait_s
    attempt = 0
    while True:
        attempt += 1
        try:
            req = urllib.request.Request(
                f"http://127.0.0.1:{host_port}/", headers={"User-Agent": "Mozilla/5.0"}
            )
            with urllib.request.urlopen(req, timeout=20) as resp:
                return resp.status, resp.read(400).decode("utf-8", "replace")[:160].replace("\n", " ")
        except urllib.error.HTTPError as e:
            return e.code, e.read(160).decode("utf-8", "replace").replace("\n", " ")
        except Exception as e:  # noqa: BLE001
            last = f"{type(e).__name__}: {e}"
            if attempt >= attempts and time.time() >= deadline:
                return None, last
            if time.time() >= deadline:
                return None, last
            time.sleep(2)


def tcp_probe(device_port: int, attempts: int = 3, wait_s: int = 0) -> bool:
    host_port = 20000 + device_port
    forward(device_port, host_port)
    deadline = time.time() + wait_s
    attempt = 0
    while True:
        attempt += 1
        try:
            socket.create_connection(("127.0.0.1", host_port), timeout=8).close()
            return True
        except Exception:  # noqa: BLE001
            if time.time() >= deadline and attempt >= attempts:
                return False
            time.sleep(2)


# ---------------------------------------------------------------- 单模板验证


def pull_until_ready(api: Api, ref: str, timeout_s: int, quiet: bool = False):
    """POST /images/pull 后轮询 /images，返回 (ok, 说明)。"""
    _, existing = api.json("GET", "/images")
    if isinstance(existing, list) and any(i["ref"] == ref for i in existing):
        return True, "cached"
    code, _ = api.json("POST", "/images/pull", {"ref": ref})
    if code not in (200, 202):
        return False, f"pull request http {code}"
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        time.sleep(5)
        _, imgs = api.json("GET", "/images")
        if isinstance(imgs, list) and any(i["ref"] == ref for i in imgs):
            return True, f"{round(time.time() - t0)}s"
        _, states = api.json("GET", "/pull-states")
        if isinstance(states, dict) and ref in states:
            st = states[ref]
            if st.get("done") and "FAILED" in str(st.get("stage")):
                return False, f"pull failed: {st}"
            if not quiet:
                mb = st.get("downloadedBytes", 0) / 1048576
                tot = (st.get("totalBytes") or 0) / 1048576
                print(
                    f"      {st.get('stage')} layer {st.get('currentLayer')}/{st.get('totalLayers')}"
                    f"  {mb:.1f}/{tot:.1f} MB",
                    flush=True,
                )
    return False, f"pull timeout after {timeout_s}s"


def verify(api: Api, tpl_id: str, *, keep: bool, out_dir: str, pull_timeout: int) -> dict:
    result: dict = {"template": tpl_id, "steps": [], "ok": False, "failures": []}

    def step(name: str, ok: bool, detail: str = "") -> bool:
        result["steps"].append({"step": name, "ok": bool(ok), "detail": detail})
        print(f"  [{'OK ' if ok else 'ERR'}] {name}: {detail}", flush=True)
        if not ok:
            result["failures"].append(f"{name}: {detail}")
        return ok

    yaml_text = compose_body(tpl_id)
    result["images"] = images_of(yaml_text)
    print(f"== {tpl_id} ==", flush=True)
    print(f"  images: {result['images']}", flush=True)

    # 1) 镜像
    result["pull"] = {}
    for ref in result["images"]:
        ok, detail = pull_until_ready(api, ref, pull_timeout)
        result["pull"][ref] = detail
        if not step(f"pull {ref}", ok, detail):
            return finish(result, api, tpl_id, keep, out_dir)

    # 2) 建项目 + up
    code, created = api.json("POST", "/compose", {"name": f"verify-{tpl_id}", "yaml": yaml_text})
    if not step("create project", code in (200, 201), f"http {code} {created}"):
        return finish(result, api, tpl_id, keep, out_dir)
    pid = created["id"]
    result["projectId"] = pid

    t0 = time.time()
    code, up = api.json("POST", f"/compose/{pid}/up")
    result["upSeconds"] = round(time.time() - t0, 1)
    if not step("compose up", code == 200, f"http {code} {up}"):
        return finish(result, api, tpl_id, keep, out_dir)
    result["startedServices"] = up.get("startedServices") if isinstance(up, dict) else up
    print(f"  up 用时 {result['upSeconds']}s，启动：{result['startedServices']}", flush=True)

    # 3) 容器进入 RUNNING
    deadline = time.time() + 180
    mine: list = []
    while time.time() < deadline:
        _, containers = api.json("GET", "/containers")
        mine = [c for c in containers if c.get("projectId") == pid] if isinstance(containers, list) else []
        if mine and all(c["status"] == "RUNNING" for c in mine):
            break
        time.sleep(4)
    result["containers"] = {c["serviceName"]: {"id": c["id"], "status": c["status"], "image": c["image"]} for c in mine}
    for c in mine:
        step(f"container {c['serviceName']} running", c["status"] == "RUNNING", c["status"])
    if not mine:
        step("containers created", False, "no container found for project")

    time.sleep(10)  # 让服务真正开始监听

    # 4) 端口探测（轮询最长 180s：n8n / Node-RED 等首启较慢，进程 RUNNING ≠ 端口就绪）
    wp = web_ports_of(tpl_id)
    result["http"] = {}
    for port in wp:
        code_, snip = http_probe(port, wait_s=180)
        result["http"][port] = {"code": code_, "snippet": snip}
        step(f"http :{port}", code_ in HTTP_OK_CODES, f"HTTP {code_} {snip[:80]}")

    result["tcp"] = {}
    for port in declared_ports(yaml_text):
        if port in wp:
            continue
        ok = tcp_probe(port, wait_s=120)
        result["tcp"][port] = ok
        step(f"tcp :{port}", ok, "connect ok" if ok else "connect fail")

    # 5) 日志（无论成败都留档，便于判定死因）
    result["logFiles"] = {}
    os.makedirs(out_dir, exist_ok=True)
    for c in mine:
        _, chunk = api.json("GET", f"/containers/{c['id']}/logs")
        lines = chunk.get("lines", []) if isinstance(chunk, dict) else []
        fp = os.path.join(out_dir, f"{tpl_id}.{c['serviceName']}.log")
        with open(fp, "w", encoding="utf-8") as f:
            f.write("\n".join(lines))
        result["logFiles"][c["serviceName"]] = fp
        print(f"  --- {c['serviceName']} 日志尾部 ---", flush=True)
        for ln in lines[-12:]:
            print("      " + ln[:180], flush=True)

    result["ok"] = not result["failures"]
    return finish(result, api, tpl_id, keep, out_dir)


def finish(result: dict, api: Api, tpl_id: str, keep: bool, out_dir: str) -> dict:
    pid = result.get("projectId")
    if pid and not keep:
        print("  清理：down + 删项目 + 删容器（镜像保留）", flush=True)
        api.json("POST", f"/compose/{pid}/down?removeContainers=true")
        api.json("DELETE", f"/compose/{pid}?confirm=true&removeContainers=true")

    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, f"{tpl_id}.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    with open(os.path.join(out_dir, "results.jsonl"), "a", encoding="utf-8") as f:
        f.write(json.dumps(result, ensure_ascii=False) + "\n")

    verdict = "PASS" if result["ok"] else "FAIL"
    print(f"\n=== {tpl_id}: {verdict} ===", flush=True)
    for f_ in result["failures"]:
        print("  失败项: " + f_, flush=True)
    print(flush=True)
    return result


# ---------------------------------------------------------------- main


def main() -> int:
    ap = argparse.ArgumentParser(description="真机逐模板验证编排模板")
    ap.add_argument("--token", default=os.environ.get("ACM_API_TOKEN"), help="Web/API 的 Bearer token")
    ap.add_argument("--host", default="127.0.0.1", help="本机转发地址，默认 127.0.0.1")
    ap.add_argument("--port", type=int, default=18765, help="本机转发端口，默认 18765")
    ap.add_argument("--only", action="append", default=[], help="只验证指定模板 id（可重复）")
    ap.add_argument("--keep", action="store_true", help="验证后保留容器不清理")
    ap.add_argument("--out", default=DEFAULT_OUT_DIR, help="结果与日志输出目录")
    ap.add_argument("--pull-timeout", type=int, default=7200, help="单镜像拉取超时秒数")
    ap.add_argument("--list", action="store_true", help="只列出模板与所需镜像")
    args = ap.parse_args()

    ids = all_template_ids()
    if args.list:
        for tpl in ids:
            body = compose_body(tpl)
            print(f"{tpl:18s} ports={declared_ports(body)}  web={web_ports_of(tpl)}  images={images_of(body)}")
        return 0

    if not args.token:
        print("缺少 --token（或环境变量 ACM_API_TOKEN）：Web/API 的 Bearer token", file=sys.stderr)
        return 2

    api = Api(f"http://{args.host}:{args.port}/api/v1", args.token)
    code, health = api.json("GET", "/settings")
    if code != 200:
        print(f"API 不通：GET /settings -> http {code} {health}", file=sys.stderr)
        print("请确认手机端 App 在运行、Web 控制台已开启，并已 adb forward。", file=sys.stderr)
        return 2
    print(f"设备: {health.get('device')}  地址: {health.get('lanAddresses')}\n", flush=True)

    targets = args.only or ids
    results = []
    for tpl in targets:
        try:
            results.append(verify(api, tpl, keep=args.keep, out_dir=args.out, pull_timeout=args.pull_timeout))
        except KeyboardInterrupt:
            print("已中断", flush=True)
            break

    print("\n================ 汇总 ================")
    for r in results:
        mark = "PASS" if r["ok"] else "FAIL"
        note = "" if r["ok"] else " | ".join(r["failures"])[:120]
        print(f"{mark:4s} {r['template']:18s} {note}")
    passed = sum(1 for r in results if r["ok"])
    print(f"\n通过 {passed}/{len(results)}（结果见 {args.out}）")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
