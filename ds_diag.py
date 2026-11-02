import re, html, urllib.request, urllib.parse, http.cookiejar

EP = "https://www.desitashan.su/anupama-2nd-november-2026-full-episode-star-plus/c3ce13fc530a2d4b/"
MOB = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
DESK = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
SEC = {
    "Sec-Fetch-Dest": "document",
    "Sec-Fetch-Mode": "navigate",
    "Sec-Fetch-Site": "cross-site",
    "Sec-Fetch-User": "?1",
    "Upgrade-Insecure-Requests": "1",
}


def rd(f):
    return open(f, encoding="utf-8", errors="ignore").read()


print("=== SEARCH: pehle result ke aas-paas ===")
s = rd("ds_s2.html")
i = s.find("full-episode")
print(s[max(0, i - 600): i + 1000] if i >= 0 else "nahi mila")


def run(ua, extra, label):
    cj = http.cookiejar.CookieJar()
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    base = {
        "User-Agent": ua,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-IN,en;q=0.9",
    }
    base.update(extra)
    h = dict(base)
    h["Referer"] = "https://www.desitashan.su/"
    ep = op.open(urllib.request.Request(EP, headers=h), timeout=25).read().decode("utf-8", "ignore")
    links = [html.unescape(x) for x in re.findall(r'https://go\.searchping\.com/desitashan/getlink\.php\?[^"]*', ep)]
    url = [l for l in links if "type=jwplayer" in l][0]
    ref = EP
    body, final = "", url
    for hop in range(1, 5):
        h = dict(base)
        h["Referer"] = ref
        r = op.open(urllib.request.Request(url, headers=h), timeout=25)
        body = r.read().decode("utf-8", "ignore")
        final = r.geturl()
        m = re.search(r'http-equiv=["\']refresh["\'][^>]*content=["\'][^"\']*url=([^"\']+)', body, re.I)
        if not m:
            break
        ref = final
        url = urllib.parse.urljoin(final, html.unescape(m.group(1)))
    t = re.search(r"<title>([^<]*)", body)
    print("\n##### %s" % label)
    print("final:", final[:200])
    print("title:", t.group(1)[:90] if t else None, "| size:", len(body))
    print("media:", re.findall(r'https?://[^\s"\'<>\\]+\.(?:m3u8|mp4|mpd)[^\s"\'<>\\]*', body)[:2])
    print("iframes:", re.findall(r'<iframe[^>]*src=["\']([^"\']+)', body, re.I)[:2])
    return body


run(MOB, {}, "mobile")
run(DESK, {}, "desktop")
body = run(DESK, SEC, "desktop + sec-fetch")
open("ds_diag_body.html", "w").write(body)

print("\n=== hop2 body: script srcs ===")
print(re.findall(r'<script[^>]*src=["\']([^"\']+)', body)[:12])
print("=== hop2 body: inline scripts (keywords wale) ===")
for sc in re.findall(r"<script[^>]*>(.*?)</script>", body, re.S):
    if len(sc) > 40 and re.search(r"player|stream|m3u8|atob|embed|location|video|redirect|desitashan", sc, re.I):
        print("---", len(sc), sc.strip()[:350].replace("\n", " "))
print("=== hop2 body: urls (desitashan / embed / stream / play) ===")
print(sorted(set(re.findall(r'https?://[^\s"\'<>\\]*(?:desitashan|embed|stream|play)[^\s"\'<>\\]*', body)))[:12])