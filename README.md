# V6 助手（V6Assist）

华为 P60 Pro（HarmonyOS 4.2 / AOSP 底座，无 root）上让 **哔哩哔哩 / 抖音** 优先走 IPv6 的手机端工具：
一个总开关 + 「省了多少校园网 v4 流量」看板 + 常驻通知栏钥匙图标。

内置 sing-box（`libbox.aar` 1.14.3，arm64-v8a）作为隧道内核，通过局域网把这两个 App 的流量转发到
同一 Wi-Fi 下的 PC 中继（PC 侧 sing-box 只走 IPv6 出站），所以在手机本身拿不到全局 IPv6 的校园网里也能用。

## 架构

```
B站/抖音 → V6Assist(tun, gvisor) → 本地 fakeip DNS → socks5 → PC:10808(sing-box) → IPv6 出站
                                   ↘ 命中直连规则的域名 → direct（不进隧道）
```

* `tun` 同时持有 `172.19.0.1/30`（v4）与 `fdfe:dcba:9876::1/126`（假 ULA v6）——
  系统层必须有 IPv6 地址，App 才会去要 AAAA 记录，这是本方案的关键。
* DNS 用 fakeip：App 拿到假地址后，sing-box 把真实域名交给 PC 端解析，PC 用 `prefer_ipv6` 拿 AAAA。
* `2404:4d00::/32`、`100::/64` 直接 reject（前者从校园网不可达，后者是抖音的连通性探测黑洞）。
* QUIC（UDP:443）reject，逼 App 走 TCP。

## 开关语义

* **开**：只对勾选的包（`tv.danmaku.bili` / `com.ss.android.ugc.aweme`）生效，其他 App 完全不受影响。
* **关**：停隧道、删路由、停前台服务、清通知，系统回到默认联网，零残留。
* 开启前会先探测中继 TCP 是否可达；不可达就拒绝开启并提示，不会把 App 弄断网。
* 检测到其他 VPN（如 Clash）在运行时禁用开关并提示，但不会去动它。

## 构建

本机无需 Android 环境，直接推到 GitHub 走 Actions（`.github/workflows/build-apk.yml`）：

1. `go install github.com/sagernet/gomobile/cmd/{gomobile,gobind}@v0.1.12`
2. 在 sing-box `v1.14.3` 源码里 `go run ./cmd/internal/build_libbox -target android -platform android/arm64` 产出 `libbox.aar`
3. 拷到 `app/libs/libbox.aar`
4. `gradle assembleDebug` → `out/v6assist-debug.apk`

`libbox.aar` 用 Actions 缓存（key `libbox-v1.14.3-arm64-r1`），命中时单次构建约 2 分钟。

## 使用

1. PC 运行 `..\一键启动中继.cmd`（sing-box 中继 + 移动热点 + 下载页）。
2. 手机连热点 `V6Relay-PC`，浏览 `http://192.168.137.1:10809/` 下载安装。
3. 打开 App → 允许 VPN → 打开总开关。中继地址默认 `192.168.137.1:10808`。
4. 关闭 = 关开关，或下拉快捷开关里的「V6 助手」磁贴。

## 已知限制

* 抖音部分广告归因域、直播拉流域（`pull-*.douyincdn.com` 等）没有 AAAA，只能走 v4。
* `2404:4d00::/32` 的字节节点从校园 CERNET 不可达，已在内核规则里 reject。
* 抖音部分媒体走 HTTPDNS，返回裸 IPv4 字面地址，这类连接只能走 v4。
* 需要 PC 中继在线；PC 关机则开关会拒绝开启（不会断网）。

## 许可

`libbox.aar` 来自 [SagerNet/sing-box](https://github.com/SagerNet/sing-box)（GPL-3.0），
移动端平台层实现参考 [SagerNet/sing-box-for-android](https://github.com/SagerNet/sing-box-for-android)。
本项目仅个人使用。
