import re, html, urllib.request, urllib.parse, http.cookiejar

EP = "https://www.desitashan.su/anupama-2nd-november-2026-full-episode-star-plus/c3ce13fc530a2d4b/"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
HDR = {
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-IN,en;q=0.9",
    "Sec-Fetch-Dest": "document",
    "Sec-Fetch-Mode": "navigate",
    "Sec-Fetch-Site": "cross-site",
    "Upgrade-Insecure-Requests": "1",
}

cj = http.cookiejar.CookieJar()
op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))


def get(url, ref, extra=None):
    h = dict(HDR)
    h["Referer"] = ref
    if extra:
        h.update(extra)
    r = op.open(urllib.request.Request(url, headers=h), timeout=25)
    return r.read().decode("utf-8", "ignore"), r.geturl()


ep, _ = get(EP, "https://www.desitashan.su/")
links = [html.unescape(x) for x in re.findall(r'https://go\.searchping\.com/desitashan/getlink\.php\?[^"]*', ep)]
url = [l for l in links if "type=jwplayer" in l][0]
ref, body, final = EP, "", url
for hop in range(1, 5):
    body, final = get(url, ref)
    m = re.search(r'http-equiv=["\']refresh["\'][^>]*content=["\'][^"\']*url=([^"\']+)', body, re.I)
    if not m:
        break
    ref = final
    url = urllib.parse.urljoin(final, html.unescape(m.group(1)))

print("FINAL:", final[:160])
base = "%s://%s" % (urllib.parse.urlparse(final).scheme, urllib.parse.urlparse(final).netloc)

print("\n=== INLINE JSON CONFIG (poora) ===")
for sc in re.findall(r"<script[^>]*>(.*?)</script>", body, re.S):
    if '"playerHost"' in sc:
        print(sc.strip()[:2600])

print("\n=== player/video container html ===")
for m in re.finditer(r'<(?:div|section|iframe|video)[^>]*(?:video|player|stream)[^>]*>', body, re.I):
    print(m.group(0)[:300])
    if m.start() > 10**9:
        break

av = [s for s in re.findall(r'<script[^>]*src=["\']([^"\']+)', body) if "article-video" in s]
print("\n=== article-video.js:", av)
if av:
    js, _ = get(urllib.parse.urljoin(final, av[0]), final, {"Sec-Fetch-Dest": "script", "Sec-Fetch-Mode": "no-cors", "Accept": "*/*"})
    open("ds_article_video.js", "w").write(js)
    print("size:", len(js))
    for kw in ["playerHost", "fetch(", "/api", "iframe", "workers", "embed", "m3u8", "token", "dataset", "vt", "sourceLinks", "jwplayer", "setup("]:
        hits = [m.start() for m in re.finditer(re.escape(kw), js)][:3]
        for p in hits:
            print("[%s] ...%s..." % (kw, js[max(0, p - 120): p + 220].replace("\n", " ")))
print("\n=== other script srcs ===")
print(re.findall(r'<script[^>]*src=["\']([^"\']+)', body)[:12])
