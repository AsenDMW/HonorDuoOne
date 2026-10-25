# Honor Duo One

[![版本](https://img.shields.io/badge/version-1.0.0-blue)](https://github.com/AsenDMW/HonorDuoOne/releases/latest)
[![许可证](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)
[![Android](https://img.shields.io/badge/Android-10%2B-green)](https://developer.android.com)
[![LSPosed](https://img.shields.io/badge/LSPosed-API%20102-orange)](https://github.com/LSPosed/LSPosed)
[![MagicOS](https://img.shields.io/badge/MagicOS-status%20bar-purple)](#要求)

把荣耀 MagicOS 状态栏右侧的 **Wi-Fi、移动信号、电池** 合并成一个仿 iPhone「Duo」风格的三合一字形，并把圆环、胶囊与双排信号三套排版合到同一个模块里。

本仓库是**复刻项目**：界面与渲染架构取自 [HonorDuo](https://github.com/odomu/HonorDuo)，液态玻璃设置页参考 [HonorHook](https://github.com/coderstory/HonorHook)，圆环与双排信号的实现思路参考 [HonorDuoBar](https://github.com/nisu123f/HonorDuoBar)，在此之上做整合、修 bug 与细节打磨。详见 [致谢与来源](#致谢与来源)。

<img src="docs/images/statusbar.png" width="100%" alt="状态栏实拍：三合一字形接管 Wi-Fi、信号与电池">

## 核心特性

- **三种排版，一个模块**：圆环、胶囊、双排信号三套样式，圆环/胶囊与双排信号互斥并自动提示。
- **三槽分区**：胶囊的上、中、下三个槽位可自由放置组件，中部最多 3 件并按三等分居中排布。
- **流量箭头组件**：单个组件内含两组上下行三角，左半移动数据、右半 Wi-Fi；未连接的一侧画灰占位，双排模式下同样可用（可单独关闭）。
- **智能防烧屏**：OLED 屏幕的 30 秒 3×3 离散网格微移位，边界安全钳制，开销极低。
- **灵动胶囊避让**：胶囊展开时按屏幕物理坐标做碰撞检测，只让被遮挡的组件让位。
- **可放置组件**：电量数字、Wi-Fi 弧、移动信号（卡一/卡二可拆）、网络制式、充电闪电、功率、温度、帧率、时钟等均可独立放置或交还系统。
- **双排信号**：主卡信号柱 + 副卡圆点，支持 4/5 格切换与制式标、流量箭头附加显示。
- **息屏正确交接**：全屏 AOD 复用状态栏行时把电池完整交还宿主，息屏不再缺电量图标。
- **时钟按需接管**：秒/日期/星期/农历/天气全关时完全不碰原生时钟。

## 安装与要求

### 要求

| 项目 | 说明 |
| :--- | :--- |
| **系统** | 荣耀 MagicOS |
| **Android** | 10 及以上（API 29+） |
| **框架** | 支持 libxposed **API 102** 的 LSPosed |
| **作用域** | 系统界面（`com.android.systemui`） |

### 安装步骤

1. 到 [Releases](https://github.com/AsenDMW/HonorDuoOne/releases) 下载最新版 `HonorDuoOne-*.apk`。
2. 打开 LSPosed 管理器，启用 **Honor Duo One**，并在作用域里勾选**系统界面**（`com.android.systemui`）。
3. 重启系统界面或重启手机生效。

> 首次使用前建议先在 LSPosed 中确认模块已激活，再进设置页调排版。

## 更新节奏

**这个项目更新不频繁，只有周末有空处理。** 平时的 bug 与想法会攒到周末一起做，着急的可以：

- 自己 `git clone` 下来，把需求丢给 AI 改完自行编译（构建步骤见下），GPL-3.0 允许你这么做；
- 在 [Issues](https://github.com/AsenDMW/HonorDuoOne/issues) 里留言，周末统一看。

## 自己构建

需要 JDK 21 与 Android SDK（platform 37 / build-tools 37）。

```bash
git clone https://github.com/AsenDMW/HonorDuoOne.git
cd HonorDuoOne
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleRelease
```

产物在 `app/build/outputs/apk/release/`。签名凭据走环境变量或 `keystore.properties`，不入库。

## AI 工具

我在用 **TRAE**，各类任务都能搞定。通过我的分享链接注册并登录桌面端，新用户一次性获得 **3,000 积分**。

🔗 <https://www.trae.cn/events/code-fission/N89SVNJ5N6M3?utm_source=copy_link&utm_medium=code_fission>

> 只有通过分享链接注册才计入，别处粘贴无效。

## 致谢与来源

这个模块是下列三个开源项目的复刻与整合，没有它们就没有这个仓库：

- **[odomu/HonorDuo](https://github.com/odomu/HonorDuo)** — 基础底座。三合一渲染引擎（`TrioRenderer`）、分区模型、液态玻璃设置页与大部分状态采样都源自这里，本项目在其之上整合三方能力并修 bug。
- **[coderstory/HonorHook](https://github.com/coderstory/HonorHook)** — 液态玻璃界面样式与部分状态栏 Hook 思路的参考实现。
- **[nisu123f/HonorDuoBar](https://github.com/nisu123f/HonorDuoBar)** — 圆环形态特性的来源，包括下拉形变、Wi-Fi 弧档位、呼吸填充、蓝牙电量、5G 回退等。

另外感谢：

- [yixing233/HyperDuo](https://github.com/yixing233/HyperDuo) —— HonorDuo 的上游；
- [Miuix](https://github.com/Design-Compute/Miuix) —— 界面组件；
- [LSPosed](https://github.com/LSPosed/LSPosed) 与 [libxposed](https://github.com/libxposed) —— 框架生态。

## 许可

本项目沿用上游的 **GPL-3.0**，详见 [LICENSE](LICENSE)。二次修改与再分发请同样开源。
