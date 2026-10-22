# 构建说明

## 这份 APK 是怎么来的

| 项 | 值 |
| --- | --- |
| 文件 | Release **v1.4-opt.1** 的附件 `qingping-debug.apk` |
| 下载 | <https://github.com/2422235579/qingping-ad-skipper/releases/download/v1.4-opt.1/qingping-debug.apk> |
| 大小 | 5.7 MB (5978938 字节) |
| SHA256 | `120B9A82B44D8C0736B3AE6F02947B156B5008DAD94257D511DB7A5DC06EDA6D` |
| MD5 | `CBD7980F901A112F52D777B56588A7C9` |
| 构建时间 | 2026-10-22 18:15:42 |
| 包名 | `com.qingping.clear` |
| 版本 | 1.4 (versionCode 5) |
| minSdk / targetSdk | 26 (Android 8.0) / 34 (Android 14) |
| 签名 | **debug 签名**（`C=US, O=Android, CN=Android Debug`） |

> 这是 **debug 构建**，不是正式签名。适合自己安装测试，不适合分发。
> 直接侧载安装即可（需要允许"安装未知来源应用"）。

---

## 构建环境（实际用到的版本）

| 组件 | 版本 / 位置 |
| --- | --- |
| JDK | **17.0.12**（Oracle，`D:\java\jdk-17.0.12`）|
| Gradle | **8.5**（AGP 8.2 要求 ≥ 8.2）|
| Android SDK | cmdline-tools `11076708`（latest）|
| platforms | `android-34` |
| build-tools | `34.0.0` |
| platform-tools | 最新 |
| AGP | 8.2.0（项目自带）|
| Kotlin | 1.9.20（项目自带）|

---

## 复现命令

`powershell
# 1. 环境变量
$env:JAVA_HOME     = 'D:\java\jdk-17.0.12'
$env:ANDROID_HOME  = 'F:\Android\sdk'
$env:ANDROID_SDK_ROOT = 'F:\Android\sdk'
$env:Path = "$env:JAVA_HOME\bin;F:\tools\gradle-8.5\bin;$env:Path"

# 2. 告诉 Gradle SDK 在哪（不要提交这个文件，已在 .gitignore 里）
echo "sdk.dir=F:\\Android\\sdk" > local.properties

# 3. 编译
gradle assembleDebug

# 产物
# app/build/outputs/apk/debug/app-debug.apk
`

---

## ⚠️ 两个必须知道的坑

### 1. 工程路径不能含中文

AGP 会直接拒绝：

`
Your project path contains non-ASCII characters.
This will most likely cause the build to fail on Windows.
`

所以构建时要把工程复制到纯英文路径（本次用的是 `F:\build\qingping`）。

> 理论上可以加 `android.overridePathCheck=true` 强行绕过，
> 但那个检查存在是因为**真会出问题**，不建议。

### 2. `services.gradle.org` 在某些网络下拉不动

本次是从**腾讯镜像**下载的 Gradle：

`
https://mirrors.cloud.tencent.com/gradle/gradle-8.5-bin.zip
`

---

## 安装注意：签名冲突

如果你手机上**已经装过**别的来源编译的清屏（哪怕是同一份源码），
直接装这个会失败：

`
INSTALL_FAILED_UPDATE_INCOMPATIBLE
`

因为 Android 要求覆盖安装的 APK 签名一致，而 debug 签名是**每台机器不同**的。

**处理办法**：先卸载旧的再装新的。
⚠️ 注意本仓库把 `allowBackup` 改成了 `false`，所以**卸载会丢拦截统计记录**。

---

## 验证过什么

| 检查 | 结果 |
| --- | --- |
| `gradle assembleDebug` | ✅ BUILD SUCCESSFUL in 2m 24s |
| Kotlin 编译（9 个文件） | ✅ 无编译错误 |
| APK 结构（aapt2 dump badging） | ✅ 包名/版本/权限正常 |
| 签名（apksigner verify） | ✅ 有效 |
| XML 资源（8 个） | ✅ 可解析 |
| **真机安装 / 实际拦截效果** | ❌ **没有设备，未验证** |

**最后一条很重要**：编译通过 ≠ 功能正确。
拦截成功率、误点率、耗电表现，都必须在真实手机上跑过才知道。