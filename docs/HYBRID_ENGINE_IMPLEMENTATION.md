# BluFox Hybrid Engine Implementation

## Current baseline (verified from source)

- Android `VpnService` is the single owner of the TUN interface.
- sing-box/libbox 1.14.4 is the only engine currently wired to that service.
- VLESS share links and VPN Gate OpenVPN profiles are converted to sing-box JSON and passed to the same libbox runtime.
- VPN Gate results currently live only in the Servers screen's in-memory list.
- The home screen shows the measured tunnel-verification duration and exit IP; upload/download counters remain unavailable and are explicitly shown as unavailable.
- The normal single-server connection flow verifies HTTPS traffic through Android TUN before reporting CONNECTED.
- The bulk candidate check currently creates one TUN session but uses the local Clash API delay endpoint to issue an HTTPS request from each sing-box outbound. This confirms outbound HTTPS reachability, not an app-originated request traversing Android TUN for every candidate. UI labels must keep this distinction explicit; do not call these results TUN-verified.

## Target architecture

```
Compose UI
  -> ConnectionManager
      -> EngineAdapter
          -> SingBoxAdapter (implemented baseline)
          -> WarpAdapter (not integrated yet)
          -> PsiphonAdapter (not integrated yet)
      -> one Android VpnService / one TUN owner
```

Only one adapter may own the Android VPN session at a time. Engine switching while connected must first stop the active adapter, confirm teardown, and then request the next engine. Do not run multiple independent Android VPN services concurrently.

## Integration decisions

### sing-box
Keep as the primary engine. It already supports the existing VLESS flow and the VPN Gate OpenVPN conversion path through libbox. Preserve current config validation and post-connect traffic verification.

### WARP / Aether
Do not label this as working until the Android native runtime, ABI packaging, identity/bootstrap flow, endpoint discovery, and license obligations are verified. The Aether path is a separate runtime integration, not a normal sing-box outbound toggle. Check all bundled native library licenses and sizes before shipping.

### Psiphon
Integrate through the official Psiphon Android SDK/tunnel-core path. Confirm the exact SDK API, foreground lifecycle, tunnel file-descriptor ownership, and GPL obligations before coding the adapter. Do not scrape or impersonate the consumer app's private endpoints.

## Required behavior

1. Persist imported server profiles locally and keep the selected profile stable across app restarts.
2. Show the active engine and selected server explicitly on Home.
3. Make engine availability truthful: unavailable engines are disabled with a clear reason, never selectable as if they can connect.
4. Route all start/stop requests through one ConnectionManager and one VpnService.
5. Add adapter-level health checks and a common connection state model.
6. Test on ARM64 Android over both mobile data and Wi-Fi, including DNS, IPv4/IPv6, reconnect, revoke, and process restart.

## Build and delivery

The GitHub Actions workflow builds the ARM64 debug APK. This branch is included in its push triggers so changes here can be built without touching `main`.

## Explicit non-goals

- Do not claim WARP or Psiphon support before their adapters actually establish and verify traffic.
- Do not use public VPN Gate servers as trusted endpoints; they are community-operated and can be unstable.
- Do not merge this branch into `main` until the APK build and device tests pass.
