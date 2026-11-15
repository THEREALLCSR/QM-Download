#!/usr/bin/env python3
"""
validate_qq_download.py — QM-Download 独立验证脚本

用途：
  1. 用当前会话复现 VKey（Android/Web 两套身份）；
  2. 检查 purl / result / retcode / server msg；
  3. HEAD 直链，确认真实 Content-Length；
  4. 可选完整下载，检查 FLAC 元数据/音频帧/时长，识别 8KB 占位文件。

只使用标准库；不会打印 Cookie / musicKey / vkey 完整值。

示例：
  python tools/validate_qq_download.py --songmid 001sNF6p25cYp3 \
      --media-mid 002bwhOz3JDXCS
  python tools/validate_qq_download.py --title "リパブリック讃歌"
  python tools/validate_qq_download.py --songmid ... --download --out test.flac
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Optional

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

MUSICU_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
UA = "QQMusic 14090008(android 15)"


# ---------------------------------------------------------------------------
# Java .properties 解析（session.properties 由 java.util.Properties.store 写入）
# ---------------------------------------------------------------------------

def _unescape_properties(value: str) -> str:
    out: list[str] = []
    i = 0
    while i < len(value):
        ch = value[i]
        if ch != "\\" or i + 1 >= len(value):
            out.append(ch)
            i += 1
            continue
        nxt = value[i + 1]
        if nxt in "=:\\":
            out.append(nxt)
            i += 2
        elif nxt == "n":
            out.append("\n")
            i += 2
        elif nxt == "t":
            out.append("\t")
            i += 2
        elif nxt == "u" and i + 5 < len(value):
            out.append(chr(int(value[i + 2:i + 6], 16)))
            i += 6
        else:
            out.append(nxt)
            i += 2
    return "".join(out)


def load_session(path: str) -> tuple[str, str, str]:
    props: dict[str, str] = {}
    with open(path, encoding="utf-8") as handle:
        for raw in handle:
            line = raw.rstrip("\r\n")
            if not line or line.startswith("#"):
                continue
            if "=" in line:
                key, value = line.split("=", 1)
            elif ":" in line:
                key, value = line.split(":", 1)
            else:
                continue
            props[_unescape_properties(key.strip())] = _unescape_properties(value)
    cookie = props.get("cookie", "")
    uin = props.get("uin", "")
    music_key = props.get("musicKey", "")
    if not cookie or not uin or not music_key:
        raise SystemExit(f"会话文件缺少字段: {path}")
    return cookie, uin, music_key


def hash33(value: str, seed: int = 5381) -> int:
    result = seed
    for ch in value:
        result = (result + ((result << 5) & 0xFFFFFFFF) + ord(ch)) & 0xFFFFFFFF
    return result & 0x7FFFFFFF


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------

def post_musicu(cookie: str, payload: dict) -> dict:
    request = urllib.request.Request(
        MUSICU_URL,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "User-Agent": UA,
            "Referer": "https://y.qq.com/",
            "Cookie": cookie,
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8", "replace"))


def comm_android(uin: str, music_key: str) -> dict:
    return {
        "ct": 11,
        "cv": 14090008,
        "v": 14090008,
        "qq": uin,
        "authst": music_key,
        "tmeAppID": "qqmusic",
        "format": "json",
    }


def comm_web(uin: str, music_key: str) -> dict:
    gtk = hash33(music_key)
    return {
        "ct": 24,
        "cv": 0,
        "uin": uin,
        "g_tk": gtk,
        "g_tk_new_20200303": gtk,
        "format": "json",
        "platform": "yqq.json",
        "chid": "0",
    }


def musicu(cookie: str, comm: dict, module: str, method: str, param: dict) -> dict:
    return post_musicu(
        cookie,
        {"comm": comm, "req_0": {"module": module, "method": method, "param": param}},
    )


# ---------------------------------------------------------------------------
# 业务
# ---------------------------------------------------------------------------

@dataclass
class VkeyResult:
    identity: str
    quality: str
    req_code: int
    result: int
    retcode: int
    msg: str
    purl: str
    sip: list[str]

    @property
    def url(self) -> str:
        if not self.purl:
            return ""
        if self.purl.startswith("http"):
            return self.purl.replace("http://", "https://", 1)
        host = ""
        for item in self.sip:
            if item and not item.startswith("http://ws"):
                host = item
                break
        if not host and self.sip:
            host = self.sip[0]
        host = host or "https://isure.stream.qqmusic.qq.com/"
        return (host.rstrip("/") + "/" + self.purl.lstrip("/")).replace("http://", "https://", 1)


def request_vkey(
    cookie: str,
    uin: str,
    music_key: str,
    identity: str,
    songmid: str,
    media_mid: Optional[str],
    prefix: str,
    ext: str,
    downloadfrom: int,
) -> VkeyResult:
    comm = comm_android(uin, music_key) if identity == "android" else comm_web(uin, music_key)
    media = media_mid or songmid
    param = {
        "uin": uin,
        "guid": uin,
        "songmid": [songmid],
        "songtype": [0],
        "filename": [f"{prefix}{media}.{ext}"],
        "vecPrefix": [prefix],
        "downloadfrom": downloadfrom,
        "source": "search",
        "ctx": 0,
    }
    response = musicu(
        cookie, comm, "music.vkey.GetVkey", "UrlGetVkey", param,
    )
    req = response.get("req_0") or {}
    data = req.get("data") or {}
    info = (data.get("midurlinfo") or [{}])[0]
    return VkeyResult(
        identity=identity,
        quality=prefix,
        req_code=int(req["code"]) if "code" in req else -1,
        result=int(info.get("result") or 0),
        retcode=int(data.get("retcode") or 0),
        msg=str(data.get("msg") or ""),
        purl=str(info.get("purl") or ""),
        sip=[str(x) for x in (data.get("sip") or [])],
    )


def safe_url(url: str) -> str:
    parsed = urllib.parse.urlsplit(url)
    return f"{parsed.scheme}://{parsed.netloc}{parsed.path}"


def head(url: str) -> tuple[int, dict[str, str]]:
    request = urllib.request.Request(
        url, method="HEAD",
        headers={"User-Agent": UA, "Referer": "https://y.qq.com/"},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, dict(response.headers)
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers or {})


def fetch_range(url: str, start: int, end: int) -> bytes:
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": UA,
            "Referer": "https://y.qq.com/",
            "Range": f"bytes={start}-{end}",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def flac_info(data: bytes) -> dict:
    if len(data) < 8 or data[:4] != b"fLaC":
        return {"is_flac": False}
    offset = 4
    last = False
    streaminfo: Optional[bytes] = None
    while not last:
        if offset + 4 > len(data):
            return {"is_flac": True, "valid": False, "reason": "metadata truncated"}
        header = data[offset]
        last = bool(header & 0x80)
        block_type = header & 0x7F
        length = int.from_bytes(data[offset + 1:offset + 4], "big")
        offset += 4
        if offset + length > len(data):
            return {"is_flac": True, "valid": False, "reason": "block truncated"}
        if block_type == 0 and streaminfo is None:
            streaminfo = data[offset:offset + length]
        offset += length
    audio_bytes = len(data) - offset
    duration_ms = None
    sample_rate = None
    total_samples = None
    if streaminfo and len(streaminfo) >= 18:
        sample_rate = ((streaminfo[10] & 0xFF) << 12) | ((streaminfo[11] & 0xFF) << 4) | ((streaminfo[12] & 0xF0) >> 4)
        total_samples = ((streaminfo[13] & 0x0F) << 32) | int.from_bytes(streaminfo[14:18], "big")
        if sample_rate:
            duration_ms = total_samples * 1000 // sample_rate
    return {
        "is_flac": True,
        "valid": audio_bytes > 64 and bool(duration_ms and duration_ms > 3000),
        "audio_bytes": audio_bytes,
        "sample_rate": sample_rate,
        "total_samples": total_samples,
        "duration_ms": duration_ms,
        "metadata_end": offset,
    }


def download(url: str, output: str) -> tuple[int, str, bytes]:
    os.makedirs(os.path.dirname(os.path.abspath(output)) or ".", exist_ok=True)
    tmp = output + ".part"
    digest = hashlib.sha256()
    received = 0
    with urllib.request.urlopen(
        urllib.request.Request(
            url,
            headers={"User-Agent": UA, "Referer": "https://y.qq.com/"},
        ),
        timeout=60,
    ) as response, open(tmp, "wb") as handle:
        total = int(response.headers.get("Content-Length") or 0)
        while True:
            block = response.read(256 * 1024)
            if not block:
                break
            handle.write(block)
            digest.update(block)
            received += len(block)
            if total:
                print(f"\r  {received * 100 // total:3d}%  {received}/{total}", end="")
    print()
    os.replace(tmp, output)
    return received, digest.hexdigest(), tmp


def find_song(cookie: str, uin: str, music_key: str, title: str) -> list[dict]:
    comm = comm_web(uin, music_key)
    response = musicu(
        cookie, comm, "music.musicasset.PlaylistBaseRead", "GetPlaylistByUin",
        {"uin": uin, "dirType": 1},
    )
    playlists = ((response.get("req_0") or {}).get("data") or {}).get("v_playlist") or []
    found: list[dict] = []
    for playlist in playlists:
        playlist_id = playlist.get("tid") or playlist.get("dirId")
        if not playlist_id:
            continue
        detail = musicu(
            cookie, comm, "music.srfDissInfo.DissInfo", "CgiGetDiss",
            {
                "disstid": int(playlist_id),
                "dirid": 0,
                "tag": True,
                "song_begin": 0,
                "song_num": 100,
                "userinfo": True,
                "orderlist": True,
                "onlysonglist": False,
            },
        )
        songs = ((detail.get("req_0") or {}).get("data") or {}).get("songlist") or []
        for song in songs:
            if title in (song.get("name") or ""):
                file = song.get("file") or {}
                found.append({
                    "playlist": playlist.get("dirName"),
                    "name": song.get("name"),
                    "songmid": song.get("mid"),
                    "media_mid": file.get("media_mid"),
                    "interval": song.get("interval"),
                    "pay": song.get("pay"),
                })
        if found:
            break
    return found


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description="QQ 音乐下载链路验证")
    parser.add_argument(
        "--session",
        default=os.path.join(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
            "desktopApp", "data", "session.properties",
        ),
    )
    parser.add_argument("--songmid")
    parser.add_argument("--media-mid")
    parser.add_argument("--title", help="按歌名在账号歌单中查找")
    parser.add_argument("--quality", choices=("lossless", "exhigh", "standard"), default="lossless")
    parser.add_argument("--downloadfrom", type=int, default=0)
    parser.add_argument("--download", action="store_true", help="完整下载并校验")
    parser.add_argument("--out")
    args = parser.parse_args()

    cookie, uin, music_key = load_session(args.session)
    print(f"账号 uin={uin} key_len={len(music_key)}")

    if args.title:
        matches = find_song(cookie, uin, music_key, args.title)
        if not matches:
            print(f"未找到歌名包含「{args.title}」的歌曲")
            return 2
        for match in matches:
            print(
                f"匹配: {match['name']} | mid={match['songmid']} "
                f"media={match['media_mid']} playlist={match['playlist']} "
                f"pay={match['pay']}"
            )
        args.songmid = matches[0]["songmid"]
        args.media_mid = matches[0]["media_mid"]
        args.out = args.out or re.sub(r'[<>:"/\\|?*]', "_", matches[0]["name"]) + ".flac"

    if not args.songmid:
        print("需要 --songmid 或 --title")
        return 2

    prefix, ext = {
        "lossless": ("F000", "flac"),
        "exhigh": ("M800", "mp3"),
        "standard": ("M500", "mp3"),
    }[args.quality]

    results: list[VkeyResult] = []
    for identity in ("android", "web"):
        result = request_vkey(
            cookie, uin, music_key, identity, args.songmid,
            args.media_mid, prefix, ext, args.downloadfrom,
        )
        results.append(result)
        print(
            f"\n[{identity}] req_code={result.req_code} result={result.result} "
            f"retcode={result.retcode} purl={'yes' if result.purl else 'no'}"
        )
        print(f"  server_msg={result.msg[:160]}")
        if result.purl:
            print(f"  url={safe_url(result.url)}")

    chosen = next((item for item in results if item.purl), None)
    if chosen is None:
        print("\n结论：两个身份都没有 purl；账号无该曲目/音质权限，或接口变更")
        return 3

    status, headers = head(chosen.url)
    length = int(headers.get("Content-Length") or 0)
    print(f"\nHEAD {status} length={length} type={headers.get('Content-Type')}")
    first = fetch_range(chosen.url, 0, 15) if status == 200 else b""
    print(f"first16={first.hex(' ')}")

    if not args.download:
        if length and length < 64 * 1024:
            print("警告：Content-Length < 64KB，可能是占位文件")
        print("\n结论：只做 HEAD 检查；加 --download 可完整校验")
        return 0

    out = args.out or f"{args.songmid}-{prefix}.{ext}"
    received, sha, _ = download(chosen.url, out)
    print(f"下载完成: {out} bytes={received} sha256={sha}")
    if ext == "flac":
        with open(out, "rb") as handle:
            info = flac_info(handle.read())
        print(f"FLAC: {info}")
        if not info.get("valid"):
            print("结论：占位/无效 FLAC，不能播放")
            return 4
    print("结论：下载文件包含有效音频")
    return 0


if __name__ == "__main__":
    sys.exit(main())
