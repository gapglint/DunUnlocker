package dev.naijun.dununlocker.proxy

internal object SetupPortalHtml {
    private val SAFE_HOST_REGEX = Regex("^[0-9a-zA-Z.:\\[\\]-]+$")

    fun sanitizeHost(rawHost: String?, fallbackIp: String): String {
        val candidate = rawHost?.trim().orEmpty()
            .let { h ->
                if (h.startsWith("[")) {
                    h.substringBefore("]") + "]"
                } else {
                    h.substringBefore(":")
                }
            }
        return if (candidate.isNotEmpty() && SAFE_HOST_REGEX.matches(candidate)) {
            candidate
        } else {
            fallbackIp
        }
    }

    fun pacScript(host: String, port: Int): String = """
        function FindProxyForURL(url, host) {
            if (isPlainHostName(host) ||
                shExpMatch(host, "*.local") ||
                host === "$host" ||
                isInNet(host, "127.0.0.0", "255.0.0.0") ||
                isInNet(host, "10.0.0.0", "255.0.0.0") ||
                isInNet(host, "172.16.0.0", "255.240.0.0") ||
                isInNet(host, "192.168.0.0", "255.255.0.0") ||
                isInNet(host, "169.254.0.0", "255.255.0.0")) {
                return "DIRECT";
            }
            return "PROXY $host:$port; DIRECT";
        }
    """.trimIndent()

    fun windowsProxyOnBat(host: String, port: Int): String {
        val pacUrl = "http://$host:$port/proxy.pac"
        return buildString {
            append("@echo off\r\n")
            append("chcp 65001 >nul\r\n")
            append("echo [DunUnlocker] Windows 자동 프록시(PAC)를 적용합니다...\r\n")
            append("reg add \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\" /v AutoConfigURL /t REG_SZ /d \"$pacUrl\" /f >nul\r\n")
            append("reg add \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\" /v ProxyEnable /t REG_DWORD /d 0 /f >nul\r\n")
            append("powershell -NoProfile -Command \"Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public class WinInet { [DllImport(\\\"wininet.dll\\\", SetLastError=true)] public static extern bool InternetSetOption(IntPtr hInternet, int dwOption, IntPtr lpBuffer, int dwBufferLength); }'; [WinInet]::InternetSetOption([IntPtr]::Zero, 39, [IntPtr]::Zero, 0) | Out-Null; [WinInet]::InternetSetOption([IntPtr]::Zero, 37, [IntPtr]::Zero, 0) | Out-Null\" >nul 2>&1\r\n")
            append("echo.\r\n")
            append("echo 적용 완료! ($pacUrl)\r\n")
            append("echo 브라우저와 앱 트래픽이 폰 프록시(TTL=64)를 경유합니다.\r\n")
            append("pause\r\n")
        }
    }

    fun windowsProxyOffBat(): String = buildString {
        append("@echo off\r\n")
        append("chcp 65001 >nul\r\n")
        append("echo [DunUnlocker] Windows 프록시 설정을 해제합니다...\r\n")
        append("reg delete \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\" /v AutoConfigURL /f >nul 2>&1\r\n")
        append("reg add \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\" /v ProxyEnable /t REG_DWORD /d 0 /f >nul\r\n")
        append("powershell -NoProfile -Command \"Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public class WinInet { [DllImport(\\\"wininet.dll\\\", SetLastError=true)] public static extern bool InternetSetOption(IntPtr hInternet, int dwOption, IntPtr lpBuffer, int dwBufferLength); }'; [WinInet]::InternetSetOption([IntPtr]::Zero, 39, [IntPtr]::Zero, 0) | Out-Null; [WinInet]::InternetSetOption([IntPtr]::Zero, 37, [IntPtr]::Zero, 0) | Out-Null\" >nul 2>&1\r\n")
        append("echo.\r\n")
        append("echo 프록시 해제 완료!\r\n")
        append("pause\r\n")
    }

    fun windowsTtlBat(ttl: Int): String = buildString {
        append("@echo off\r\n")
        append("chcp 65001 >nul\r\n")
        append("net session >nul 2>&1\r\n")
        append("if %errorlevel% neq 0 (\r\n")
        append("  echo 관리자 권한을 요청합니다...\r\n")
        append("  powershell -NoProfile -Command \"Start-Process -FilePath '%~f0' -Verb RunAs\"\r\n")
        append("  exit /b\r\n")
        append(")\r\n")
        append("netsh int ipv4 set global defaultcurhoplimit=$ttl\r\n")
        append("netsh int ipv6 set global defaultcurhoplimit=$ttl\r\n")
        if (ttl == 65) {
            append("reg delete \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\" /v AutoConfigURL /f >nul 2>&1\r\n")
            append("reg add \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\" /v ProxyEnable /t REG_DWORD /d 0 /f >nul 2>&1\r\n")
            append("powershell -NoProfile -Command \"Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public class WinInet { [DllImport(\\\"wininet.dll\\\", SetLastError=true)] public static extern bool InternetSetOption(IntPtr hInternet, int dwOption, IntPtr lpBuffer, int dwBufferLength); }'; [WinInet]::InternetSetOption([IntPtr]::Zero, 39, [IntPtr]::Zero, 0) | Out-Null; [WinInet]::InternetSetOption([IntPtr]::Zero, 37, [IntPtr]::Zero, 0) | Out-Null\" >nul 2>&1\r\n")
            append("echo.\r\n")
            append("echo [DunUnlocker] IPv4/IPv6 기본 TTL이 $ttl 로 설정되었으며, 중복 적용 방지를 위해 기존 프록시는 해제했습니다.\r\n")
        } else {
            append("echo.\r\n")
            append("echo [DunUnlocker] IPv4/IPv6 기본 TTL이 $ttl 로 복구되었습니다.\r\n")
        }
        append("pause\r\n")
    }

    fun macProxyOnSh(host: String, port: Int): String {
        val pacUrl = "http://$host:$port/proxy.pac"
        return """
            #!/bin/bash
            set -e
            SERVICE=$(networksetup -listallnetworkservices | grep -E '^(Wi-Fi|iPhone USB|USB|RNDIS|Ethernet)' | head -n 1)
            if [ -z "${'$'}SERVICE" ]; then SERVICE="Wi-Fi"; fi
            echo "[DunUnlocker] Applying PAC proxy to ${'$'}SERVICE -> $pacUrl"
            networksetup -setautoproxyurl "${'$'}SERVICE" "$pacUrl"
            networksetup -setautoproxystate "${'$'}SERVICE" on
            echo "Done!"
        """.trimIndent() + "\n"
    }

    fun macProxyOffSh(): String = """
        #!/bin/bash
        set -e
        SERVICE=$(networksetup -listallnetworkservices | grep -E '^(Wi-Fi|iPhone USB|USB|RNDIS|Ethernet)' | head -n 1)
        if [ -z "${'$'}SERVICE" ]; then SERVICE="Wi-Fi"; fi
        echo "[DunUnlocker] Disabling PAC proxy on ${'$'}SERVICE"
        networksetup -setautoproxystate "${'$'}SERVICE" off
        echo "Done!"
    """.trimIndent() + "\n"

    fun linuxTtlSh(host: String, port: Int, ttl: Int): String = """
        #!/bin/bash
        set -e
        echo "[DunUnlocker] Setting IPv4 default TTL to $ttl..."
        sudo sysctl -w net.ipv4.ip_default_ttl=$ttl
        echo "Done! (For proxy mode: export http_proxy=http://$host:$port https_proxy=http://$host:$port ALL_PROXY=socks5h://$host:$port)"
    """.trimIndent() + "\n"

    fun portalHtml(host: String, port: Int, clientIp: String): String {
        val proxyAddr = "$host:$port"
        val pacUrl = "http://$host:$port/proxy.pac"
        return """
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DunUnlocker 테더링 셋업</title>
<style>
  :root { color-scheme: light dark; --bg:#0f172a; --card:#1e293b; --fg:#f8fafc; --muted:#94a3b8; --accent:#38bdf8; --accent-fg:#082f49; --border:#334155; --ok:#22c55e; }
  @media (prefers-color-scheme: light) { :root { --bg:#f8fafc; --card:#ffffff; --fg:#0f172a; --muted:#475569; --accent:#0284c7; --accent-fg:#ffffff; --border:#e2e8f0; --ok:#16a34a; } }
  * { box-sizing:border-box; }
  body { margin:0; font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif; background:var(--bg); color:var(--fg); line-height:1.55; }
  .wrap { max-width:720px; margin:0 auto; padding:24px 16px 48px; }
  h1 { font-size:1.5rem; margin:0 0 6px; }
  .sub { color:var(--muted); margin:0 0 20px; font-size:0.95rem; }
  .badge { display:inline-block; background:rgba(34,197,94,.16); color:var(--ok); padding:3px 10px; border-radius:999px; font-size:.82rem; font-weight:600; margin-bottom:12px; }
  .card { background:var(--card); border:1px solid var(--border); border-radius:16px; padding:18px; margin-bottom:16px; }
  .card h2 { font-size:1.05rem; margin:0 0 10px; }
  .kv { display:flex; align-items:center; justify-content:space-between; gap:10px; padding:10px 12px; border-radius:10px; background:rgba(148,163,184,.1); margin-bottom:8px; flex-wrap:wrap; }
  .kv code { font-family:ui-monospace,SFMono-Regular,Consolas,monospace; font-size:.92rem; word-break:break-all; }
  .btn, button { display:inline-flex; align-items:center; justify-content:center; gap:6px; padding:9px 14px; border-radius:10px; border:1px solid transparent; font-weight:600; font-size:.9rem; cursor:pointer; text-decoration:none; }
  .btn-primary { background:var(--accent); color:var(--accent-fg); }
  .btn-outline { background:transparent; color:var(--fg); border-color:var(--border); }
  .btns { display:flex; flex-wrap:wrap; gap:10px; margin-top:10px; }
  .tabs { display:flex; gap:8px; flex-wrap:wrap; margin-bottom:14px; }
  .tab { padding:7px 13px; border-radius:999px; border:1px solid var(--border); background:transparent; color:var(--fg); font-size:.88rem; cursor:pointer; }
  .tab.active { background:var(--accent); color:var(--accent-fg); border-color:transparent; }
  .panel { display:none; }
  .panel.active { display:block; }
  pre { background:rgba(148,163,184,.12); padding:12px; border-radius:10px; overflow-x:auto; font-size:.84rem; margin:8px 0; }
  .hint { color:var(--muted); font-size:.86rem; margin-top:8px; }
  .warn { background:rgba(245,158,11,.14); border:1px solid rgba(245,158,11,.45); border-radius:12px; padding:12px 14px; font-size:.88rem; margin-bottom:16px; }
</style>
</head>
<body>
<div class="wrap">
  <span class="badge">● 프록시 실행 중 · 연결 기기 $clientIp</span>
  <h1>DunUnlocker 테더링 셋업</h1>
  <p class="sub">연결된 기기에 맞는 우회 방법 <strong>한 가지</strong>만 선택해 적용하세요.</p>

  <div class="warn">
    <strong>⚠️ 둘 중 하나만 적용하세요 (동시 사용 비권장)</strong><br>
    • <strong>PC (Windows / macOS / Linux)</strong>: <strong>TTL=65</strong>를 권장합니다. 프록시 없이 직접 연결되어 지연이 없고 UDP·WebRTC·게임 스트리밍까지 모두 지원합니다.<br>
    • <strong>프록시(PAC)</strong>: 관리자 권한이 없는 PC나 TTL을 바꿀 수 없는 모바일(iOS/iPadOS)에서만 사용하세요. HTTP 프록시는 TCP 전용이므로 UDP·실시간 게임에는 지연이 커질 수 있습니다.
  </div>

  <div class="card">
    <h2>기기별 원클릭 셋업</h2>
    <div class="tabs">
      <button class="tab" data-os="win" onclick="showTab('win')">Windows</button>
      <button class="tab" data-os="mac" onclick="showTab('mac')">macOS</button>
      <button class="tab" data-os="linux" onclick="showTab('linux')">Linux</button>
      <button class="tab" data-os="mobile" onclick="showTab('mobile')">iOS / iPadOS / Android</button>
    </div>

    <div id="panel-win" class="panel">
      <p><strong>방법 1 · TTL=65 직접 연결 (권장 · 게임/UDP/모든 앱 지원, 지연 없음)</strong></p>
      <div class="btns">
        <a class="btn btn-primary" href="/download/win-ttl-65.bat">TTL=65 적용 (.bat)</a>
        <a class="btn btn-outline" href="/download/win-ttl-reset.bat">TTL 기본값(128) 복구 (.bat)</a>
      </div>
      <p class="hint">관리자 권한이 필요하며, 실행 시 중복 방지를 위해 기존 시스템 프록시도 함께 해제합니다. 적용 후에는 폰 프록시를 꺼도 됩니다.</p>

      <p style="margin-top:18px"><strong>방법 2 · 프록시 자동 적용 (관리자 권한 없을 때만 · 웹/TCP 전용)</strong></p>
      <div class="btns">
        <a class="btn btn-outline" href="/download/win-proxy-on.bat">프록시 자동 적용 (.bat)</a>
        <a class="btn btn-outline" href="/download/win-proxy-off.bat">프록시 해제 (.bat)</a>
      </div>
      <p class="hint">관리자 권한 없이 웹 브라우징 위주로 사용할 때만 선택하세요. UDP/WebRTC 게임 스트리밍에는 비권장입니다.</p>
    </div>

    <div id="panel-mac" class="panel">
      <p><strong>방법 1 · TTL=65 직접 연결 (권장 · 게임/UDP/모든 앱 지원, 지연 없음)</strong></p>
      <pre id="mac-ttl">sudo sysctl -w net.inet.ip.ttl=65 net.inet6.ip6.hlim=65</pre>
      <button class="btn-primary" onclick="copyText('mac-ttl', this)">명령어 복사</button>
      <p class="hint">적용 후에는 폰 프록시 없이 직접 연결로 사용하세요.</p>

      <p style="margin-top:18px"><strong>방법 2 · 자동 프록시 (관리자 권한 없을 때만 · 웹/TCP 전용)</strong></p>
      <div class="btns">
        <a class="btn btn-outline" href="/download/mac-proxy-on.sh">프록시 적용 (.sh)</a>
        <a class="btn btn-outline" href="/download/mac-proxy-off.sh">프록시 해제 (.sh)</a>
      </div>
    </div>

    <div id="panel-linux" class="panel">
      <p><strong>방법 1 · TTL=65 직접 연결 (권장 · 게임/UDP/모든 앱 지원, 지연 없음)</strong></p>
      <pre id="linux-ttl">sudo sysctl -w net.ipv4.ip_default_ttl=65</pre>
      <div class="btns">
        <button class="btn-primary" onclick="copyText('linux-ttl', this)">명령어 복사</button>
        <a class="btn btn-outline" href="/download/linux-ttl.sh">스크립트 다운로드 (.sh)</a>
      </div>

      <p style="margin-top:18px"><strong>방법 2 · 프록시 환경변수 (sudo 권한 없을 때만 · 웹/TCP 전용)</strong></p>
      <pre id="linux-proxy">export http_proxy="http://$proxyAddr" https_proxy="http://$proxyAddr" ALL_PROXY="socks5h://$proxyAddr"</pre>
      <button class="btn-outline" onclick="copyText('linux-proxy', this)">명령어 복사</button>
    </div>

    <div id="panel-mobile" class="panel">
      <p><strong>iPhone / iPad · 자동 프록시 (PAC)</strong></p>
      <p class="hint">모바일 기기는 TTL 변경이 불가능하므로 PAC 프록시를 사용합니다 (웹/TCP 위주).<br>설정 → Wi-Fi → 연결된 핫스팟 옆 <strong>(i)</strong> → 맨 아래 <strong>프록시 구성</strong> → <strong>자동</strong> 선택 → 아래 <strong>PAC 주소</strong> 붙여넣기</p>
      <p style="margin-top:12px"><strong>Android · 자동 프록시 (PAC)</strong></p>
      <p class="hint">설정 → Wi-Fi → 연결된 핫스팟 설정 → 프록시 → <strong>자동 구성</strong> 선택 → 아래 <strong>PAC 주소</strong> 입력 (또는 수동: 호스트 <code>$host</code>, 포트 <code>$port</code>)</p>
    </div>
  </div>

  <div class="card">
    <h2>프록시 연결 정보 (프록시 모드 사용 시)</h2>
    <div class="kv">
      <div><strong>자동 프록시 (PAC)</strong><br><code id="pac">$pacUrl</code></div>
      <button class="btn-outline" onclick="copyText('pac', this)">복사</button>
    </div>
    <div class="kv">
      <div><strong>수동 프록시 (HTTP / SOCKS5)</strong><br><code id="addr">$proxyAddr</code></div>
      <button class="btn-outline" onclick="copyText('addr', this)">복사</button>
    </div>
  </div>
</div>
<script>
function showTab(os) {
  document.querySelectorAll('.tab').forEach(t => t.classList.toggle('active', t.dataset.os === os));
  document.querySelectorAll('.panel').forEach(p => p.classList.toggle('active', p.id === 'panel-' + os));
}
function copyText(id, btn) {
  const txt = document.getElementById(id).textContent.trim();
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(txt).then(() => { const o = btn.textContent; btn.textContent = '복사됨'; setTimeout(() => btn.textContent = o, 1500); });
  } else {
    const ta = document.createElement('textarea'); ta.value = txt; document.body.appendChild(ta); ta.select(); document.execCommand('copy'); document.body.removeChild(ta);
    const o = btn.textContent; btn.textContent = '복사됨'; setTimeout(() => btn.textContent = o, 1500);
  }
}
(function() {
  const ua = navigator.userAgent || '';
  if (/Windows/i.test(ua)) showTab('win');
  else if (/Macintosh|Mac OS X/i.test(ua) && !/iPhone|iPad/i.test(ua)) showTab('mac');
  else if (/iPhone|iPad|Android/i.test(ua)) showTab('mobile');
  else showTab('linux');
})();
</script>
</body>
</html>
        """.trimIndent()
    }
}
