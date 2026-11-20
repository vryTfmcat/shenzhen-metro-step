import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Timer

ROOT = Path(__file__).resolve().parent
PORT = int(os.environ.get("METRO_PORT", "8765"))
ORIGIN = f"http://127.0.0.1:{PORT}"
FILES = {"/": ("index.html", "text/html"), "/app.js": ("app.js", "text/javascript"),
         "/style.css": ("style.css", "text/css")}
COORD = re.compile(r"(-?\d{1,3}(?:\.\d{1,6})?),(-?\d{1,2}(?:\.\d{1,6})?)")
KEY = re.compile(r"[a-fA-F0-9]{32}")


def coordinates(raw):
    value = raw if isinstance(raw, str) else ""
    match = COORD.fullmatch(value)
    if not match or not (113.65 <= float(match[1]) <= 114.7 and 22.38 <= float(match[2]) <= 22.9):
        raise ValueError("请输入深圳范围内的起点和终点。")
    return value


def amap(path, key, **params):
    url = "https://restapi.amap.com" + path + "?" + urllib.parse.urlencode({"key": key, **params})
    try:
        with urllib.request.urlopen(url, timeout=12) as response:
            data = json.load(response)
    except (OSError, ValueError):
        raise ValueError("暂时无法连接高德，请检查网络后重试。") from None
    if str(data.get("status")) != "1":
        code = str(data.get("infocode", ""))
        if code in {"10001", "10005", "10006", "10007", "10009", "10012"}:
            raise ValueError(f"高德拒绝了请求（{code}）。请检查 Web 服务 Key 及其调用限制。")
        raise ValueError(f"高德请求失败（{code if code.isdigit() else '未知错误'}）。")
    return data


class Handler(BaseHTTPRequestHandler):
    key = os.environ.get("AMAP_WEB_KEY", "").strip()

    def log_message(self, *_):
        pass

    def send_data(self, status, content_type, payload):
        self.send_response(status)
        self.send_header("Content-Type", content_type + "; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'self'; img-src 'self' blob:; "
                         "object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
        self.end_headers()
        self.wfile.write(payload)

    def reply(self, value, status=200):
        self.send_data(status, "application/json", json.dumps(value, ensure_ascii=False).encode("utf-8"))

    def local_request(self):
        if self.headers.get("Host") != f"127.0.0.1:{PORT}":
            self.reply({"error": "请通过应用的本机地址打开。"}, 403)
            return False
        return True

    def do_GET(self):
        if not self.local_request():
            return
        if self.path == "/api/status":
            self.reply({"configured": bool(KEY.fullmatch(type(self).key))})
            return
        item = FILES.get(self.path)
        if item:
            name, kind = item
            self.send_data(200, kind, (ROOT / name).read_bytes())
        else:
            self.reply({"error": "页面不存在。"}, 404)

    def do_POST(self):
        if not self.local_request():
            return
        if self.headers.get("Origin") != ORIGIN or self.headers.get_content_type() != "application/json":
            self.reply({"error": "请求来源无效。"}, 403)
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 4096:
                raise ValueError("请求长度无效。")
            body = json.loads(self.rfile.read(size))
            if not isinstance(body, dict):
                raise ValueError("请求格式无效。")
            self.reply(self.handle_api(body))
        except (json.JSONDecodeError, UnicodeDecodeError):
            self.reply({"error": "请求格式无效。"}, 400)
        except ValueError as error:
            self.reply({"error": str(error)}, 400)

    def handle_api(self, body):
        key = type(self).key
        if self.path == "/api/key":
            new_key = body.get("key")
            if not isinstance(new_key, str) or not KEY.fullmatch(new_key):
                raise ValueError("请输入 32 位高德 Web 服务 Key。")
            amap("/v3/assistant/inputtips", new_key, keywords="会展中心地铁站",
                 city="0755", citylimit="true")
            type(self).key = new_key
            return {"configured": True}
        if not KEY.fullmatch(key):
            raise ValueError("请先在高德设置中连接 Web 服务 Key。")
        if self.path == "/api/search":
            word = body.get("word")
            if not isinstance(word, str) or not 1 <= len(word.strip()) <= 60:
                raise ValueError("请输入 1 至 60 字的站名或地点名称。")
            result = amap("/v3/assistant/inputtips", key, keywords=word.strip(), city="0755",
                          citylimit="true", datatype="all")
            places = []
            seen = set()
            for tip in result.get("tips") or []:
                name = tip.get("name")
                try:
                    location = coordinates(tip.get("location"))
                except ValueError:
                    continue
                if isinstance(name, str) and (name, location) not in seen:
                    seen.add((name, location))
                    district = tip.get("district")
                    address = tip.get("address")
                    places.append({"name": name, "location": location,
                                   "detail": " · ".join(part for part in (district, address)
                                                       if isinstance(part, str) and part)})
            return {"places": places}
        if self.path == "/api/route":
            origin = coordinates(body.get("origin"))
            destination = coordinates(body.get("destination"))
            if origin == destination:
                raise ValueError("起点和终点相同，请更换其中一个地点。")
            strategy = "2" if body.get("strategy") == "2" else "0"
            return amap("/v3/direction/transit/integrated", key, origin=origin, destination=destination,
                        city="0755", strategy=strategy, extensions="all")
        raise ValueError("请求地址无效。")


if __name__ == "__main__":
    try:
        server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    except OSError:
        print(f"无法监听 {ORIGIN}。如果应用已经启动，请直接在浏览器打开此地址。")
        raise SystemExit(1) from None
    print(f"地铁单步导航  {ORIGIN}  按 Ctrl+C 停止", flush=True)
    if os.environ.get("METRO_NO_BROWSER") != "1":
        Timer(0.25, lambda: webbrowser.open(ORIGIN)).start()
    with server:
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
