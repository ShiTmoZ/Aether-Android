# Aether MASQUE Android Client 🛡️

Ultra-clean, modern Android client for Cloudflare WARP MASQUE (RFC 9298 over HTTP/2) designed with **SetProduct Liquid Glass / Crystal Ice Obsidian** aesthetics.

![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-blue?style=flat-square)
![Architecture](https://img.shields.io/badge/Architecture-arm64--v8a-green?style=flat-square)
![Protocol](https://img.shields.io/badge/Protocol-MASQUE%20H2%20(RFC%209298)-purple?style=flat-square)
![UI](https://img.shields.io/badge/UI-Jetpack%20Compose%20%7C%20Liquid%20Glass-orange?style=flat-square)

---

## Features

- **SetProduct Liquid Glass Obsidian UI**: Pure Jetpack Compose dark aesthetic with specular meniscus reflections, glowing optic lens toggle, and haptic feedback.
- **Cloudflare MASQUE (RFC 9298)**: Bypasses DPI filtering by camouflaging traffic as standard HTTPS (HTTP/2 on port 443) to Cloudflare edge anycast nodes.
- **Turbo Auto-Discovery**: Automatic millisecond latency probing to find the lowest-RTT edge gateway without interactive prompts.
- **Lossless MTU 1360**: Eliminates packet fragmentation and retransmissions on mobile and home broadband networks.
- **JNI L3 TUN Engine**: Lightweight `hev-socks5-tunnel` integration directly within Android `VpnService` with zero root requirement.
- **Real-Time Telemetry**: Live RTT/latency indicators, protocol badges, uptime counter, and collapsible color-coded diagnostic log drawer.

---

## Architecture

```
[ Android Apps ]
       │
       ▼ (TUN Interface, MTU 1360)
[ VpnService ]
       │
       ▼ (FD passing via JNI)
[ hev-socks5-tunnel ]
       │
       ▼ (SOCKS5 127.0.0.1:10808)
[ Aether Core (Rust Engine) ]
       │
       ▼ (MASQUE over HTTP/2, TLS 1.3)
[ Cloudflare Global Anycast Edge (162.159.192.0/18:443) ]
```

---

## Download

Grab the latest standalone APK from the [Releases](https://github.com/ShiTmoZ/Aether-Android/releases) tab.
