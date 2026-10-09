#!/usr/bin/env python3
"""
ดึง HTML ดิบของหน้าฮีโร่ทุกตัวจาก rovmeta.com (ภาษาไทย) ครั้งเดียว แล้วรวมเป็นไฟล์ zip

- ใช้เฉพาะ standard library ไม่ต้อง pip install
- อ่าน robots.txt ก่อน ถ้าไม่อนุญาตจะหยุดเอง และเคารพ Crawl-delay ถ้ามี
- หน่วง 1.2-2.0 วินาทีต่อหน้า (ปรับได้) ยิงทีละหน้า ไม่ขนาน
- มีแคช: หน้าที่โหลดแล้วจะไม่โหลดซ้ำ รันซ้ำเพื่อเก็บตกได้
- ถูกบล็อก (403/429 ซ้ำ) หรือพังติดกันหลายหน้า จะหยุดทันที และยังได้ zip เท่าที่โหลดมา

วิธีใช้:
    python tools/fetch_rovmeta.py                # ทั้งหมด
    python tools/fetch_rovmeta.py --limit 3      # ลองก่อน 3 หน้า
ผลลัพธ์:
    out/rovmeta_raw/            HTML ดิบ + manifest.json
    out/rovmeta_raw.zip         ไฟล์เดียวกันแบบ zip
"""
import argparse
import gzip
import json
import random
import re
import sys
import time
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from urllib import error, request, robotparser
from urllib.parse import urljoin, urlparse

UA = "RoVGuideApp-OneTimeFetch/1.0 (personal hobby project; one request at a time, 1-2s apart)"
LIST_PATH = "/th/heroes"
HERO_PATH_RE = re.compile(r"^/th/heroes/([A-Za-z0-9_\-]+)/?$")
MAX_CONSECUTIVE_FAILURES = 5
BACKOFF = 30  # วินาที ฐานของการรอเมื่อโดน 429 (คูณตามรอบที่ลอง)


class Blocked(Exception):
    """เว็บบล็อก/จำกัดอัตราซ้ำ ต้องหยุดทั้งหมด"""


def http_get(url, timeout=30):
    req = request.Request(
        url,
        headers={
            "User-Agent": UA,
            "Accept": "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5",
            "Accept-Language": "th,en;q=0.8",
            "Accept-Encoding": "gzip",
        },
    )
    with request.urlopen(req, timeout=timeout) as resp:
        data = resp.read()
        if (resp.headers.get("Content-Encoding") or "").lower() == "gzip":
            data = gzip.decompress(data)
        return resp.status, data


def fetch(url, retries=3):
    """GET พร้อม retry แบบสุภาพ; 403 หรือ 429 ซ้ำจนหมดรอบ => Blocked"""
    for attempt in range(1, retries + 1):
        try:
            return http_get(url)
        except error.HTTPError as e:
            if e.code in (403, 429):
                if e.code == 403 or attempt == retries:
                    raise Blocked(f"HTTP {e.code} จาก {url}")
                wait = e.headers.get("Retry-After", "")
                wait = int(wait) if wait.isdigit() else BACKOFF * attempt
                time.sleep(min(wait, 120))
                continue
            if 500 <= e.code < 600 and attempt < retries:
                time.sleep(min(5, BACKOFF) * attempt)
                continue
            raise
        except (error.URLError, TimeoutError, ConnectionError):
            if attempt == retries:
                raise
            time.sleep(min(5, BACKOFF) * attempt)


def load_robots(base):
    """คืน (RobotFileParser, crawl_delay, ข้อความ robots)  หยุดถ้าตรวจไม่ได้"""
    url = base + "/robots.txt"
    rp = robotparser.RobotFileParser()
    try:
        _, data = fetch(url)
        text = data.decode("utf-8", errors="replace")
        rp.parse(text.splitlines())
    except error.HTTPError as e:
        if e.code == 404:
            text = ""
            rp.parse([])  # ไม่มี robots.txt = ไม่มีข้อห้าม
        else:
            raise SystemExit(f"อ่าน robots.txt ไม่ได้ (HTTP {e.code}) หยุดเพื่อความปลอดภัย")
    except Blocked as e:
        raise SystemExit(f"ถูกบล็อกตั้งแต่ robots.txt: {e}")
    except Exception as e:  # เครือข่ายล่ม ฯลฯ
        raise SystemExit(f"อ่าน robots.txt ไม่ได้ ({e}) หยุดเพื่อความปลอดภัย")
    delay = rp.crawl_delay(UA)
    return rp, (float(delay) if delay else 0.0), text


def extract_slugs(list_html, list_url):
    base_host = urlparse(list_url).netloc
    seen, slugs = set(), []
    for raw in re.findall(r"""href=["']([^"']+)["']""", list_html, flags=re.I):
        absolute = urljoin(list_url, raw.replace("&amp;", "&"))
        p = urlparse(absolute)
        if p.netloc != base_host:
            continue
        m = HERO_PATH_RE.match(p.path)
        if m and m.group(1) not in seen:
            seen.add(m.group(1))
            slugs.append(m.group(1))
    return slugs


def write_zip(raw_dir, zip_path):
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for f in sorted(raw_dir.iterdir()):
            if f.is_file():
                z.write(f, arcname=f"rovmeta_raw/{f.name}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", default="https://rovmeta.com")
    ap.add_argument("--out", default="out")
    ap.add_argument("--limit", default="0", help="0 หรือว่าง = ทั้งหมด")
    ap.add_argument("--min-delay", type=float, default=1.2)
    ap.add_argument("--jitter", type=float, default=0.8)
    ap.add_argument("--backoff", type=float, default=30, help="ฐานเวลารอเมื่อโดน 429 (วินาที)")
    ap.add_argument("--force", action="store_true", help="โหลดใหม่ แม้มีแคช")
    args = ap.parse_args()

    global BACKOFF
    BACKOFF = args.backoff
    base = args.base.rstrip("/")
    limit = int(args.limit) if str(args.limit).strip().isdigit() else 0
    out = Path(args.out)
    raw = out / "rovmeta_raw"
    raw.mkdir(parents=True, exist_ok=True)

    manifest = {
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "base": base,
        "list_url": base + LIST_PATH,
        "user_agent": UA,
        "pages": [],
        "failed": [],
        "stopped_early": None,
    }

    def save_manifest():
        (raw / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")

    # 1) robots.txt
    rp, crawl_delay, robots_text = load_robots(base)
    (raw / "_robots.txt").write_text(robots_text, encoding="utf-8")
    manifest["crawl_delay_from_robots"] = crawl_delay
    delay = max(args.min_delay, crawl_delay)

    list_url = base + LIST_PATH
    if not rp.can_fetch(UA, list_url):
        manifest["stopped_early"] = "robots.txt ไม่อนุญาตหน้ารายชื่อฮีโร่"
        save_manifest()
        write_zip(raw, out / "rovmeta_raw.zip")
        raise SystemExit("robots.txt ไม่อนุญาตหน้ารายชื่อฮีโร่ หยุดการทำงาน")

    # 2) หน้ารายชื่อ
    list_file = raw / "_heroes_list.html"
    if list_file.exists() and list_file.stat().st_size > 0 and not args.force:
        list_html = list_file.read_text(encoding="utf-8", errors="replace")
    else:
        try:
            _, data = fetch(list_url)
        except Exception as e:
            raise SystemExit(f"โหลดหน้ารายชื่อไม่ได้: {e}")
        list_file.write_bytes(data)
        list_html = data.decode("utf-8", errors="replace")
        time.sleep(delay + random.uniform(0, args.jitter))

    slugs = extract_slugs(list_html, list_url)
    if not slugs:
        manifest["stopped_early"] = "ไม่พบลิงก์ฮีโร่ในหน้ารายชื่อ (โครงสร้างหน้าเปลี่ยน?)"
        save_manifest()
        write_zip(raw, out / "rovmeta_raw.zip")
        raise SystemExit(manifest["stopped_early"])
    manifest["hero_count_found"] = len(slugs)
    if limit:
        slugs = slugs[:limit]
    print(f"พบฮีโร่ {manifest['hero_count_found']} ตัว, จะดึง {len(slugs)} หน้า, หน่วง >= {delay:.1f}s")

    # 3) หน้าฮีโร่
    fetched = cached = 0
    consecutive_fail = 0
    try:
        for i, slug in enumerate(slugs, 1):
            url = f"{base}{LIST_PATH}/{slug}"
            dest = raw / f"{slug}.html"

            if dest.exists() and dest.stat().st_size > 0 and not args.force:
                data = dest.read_bytes()
                cached += 1
                manifest["pages"].append(
                    {"slug": slug, "bytes": len(data), "from_cache": True, "has_abilities": b"abilities" in data}
                )
                continue

            if not rp.can_fetch(UA, url):
                manifest["failed"].append({"slug": slug, "error": "ถูก robots.txt ห้าม"})
                continue

            try:
                status, data = fetch(url)
                if status != 200 or len(data) < 5000:
                    raise ValueError(f"ผิดปกติ status={status} bytes={len(data)}")
                dest.write_bytes(data)
                fetched += 1
                consecutive_fail = 0
                manifest["pages"].append(
                    {"slug": slug, "bytes": len(data), "from_cache": False, "has_abilities": b"abilities" in data}
                )
            except Blocked as e:
                manifest["failed"].append({"slug": slug, "error": str(e)})
                manifest["stopped_early"] = f"เว็บบล็อกหรือจำกัดอัตรา: {e}"
                break
            except Exception as e:
                manifest["failed"].append({"slug": slug, "error": str(e)})
                consecutive_fail += 1
                if consecutive_fail >= MAX_CONSECUTIVE_FAILURES:
                    manifest["stopped_early"] = f"พังติดกัน {MAX_CONSECUTIVE_FAILURES} หน้า"
                    break

            if i % 25 == 0:
                print(f"  {i}/{len(slugs)}")
            time.sleep(delay + random.uniform(0, args.jitter))
    finally:
        manifest["summary"] = {
            "requested": len(slugs),
            "downloaded": fetched,
            "from_cache": cached,
            "failed": len(manifest["failed"]),
            "pages_without_abilities": [p["slug"] for p in manifest["pages"] if not p["has_abilities"]],
        }
        save_manifest()
        zip_path = out / "rovmeta_raw.zip"
        write_zip(raw, zip_path)

    s = manifest["summary"]
    print(
        f"เสร็จ: โหลดใหม่ {s['downloaded']}, ใช้แคช {s['from_cache']}, พลาด {s['failed']}, "
        f"ไม่พบสกิลในหน้า {len(s['pages_without_abilities'])}"
    )
    if manifest["stopped_early"]:
        print("หยุดก่อนจบ:", manifest["stopped_early"])
    print(f"ไฟล์: {zip_path} ({zip_path.stat().st_size / 1_000_000:.1f} MB)")
    sys.exit(1 if (manifest["failed"] or manifest["stopped_early"]) else 0)


if __name__ == "__main__":
    main()
