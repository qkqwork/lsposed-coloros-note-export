# 发布与上架

这份文档写两件事：怎么从源码做出一个可以发布的版本，以及怎么让它出现在
[LSPosed 的模块仓库](https://modules.lsposed.org) 里。命令都是 Windows PowerShell。

## 1. 版本号在哪里

只有一个地方：`AndroidManifest.xml` 顶部的两个属性。

```xml
<manifest android:versionCode="1" android:versionName="1.0">
```

- `versionCode` 是**整数**，只能涨不能降：Android 与 LSPosed 都靠它判断"这是新版本"。
  已经发布过的版本不要再改这个数字，否则装了旧版的手机会拒绝升级。
- `versionName` 是给人看的，随便写（`1.0`、`1.1`、`1.2.1` 都行）。
- 设置页最下面的「关于」会自己把这两个值读出来显示，不需要手写第二遍。

改完版本号，**先去 README 顶部同步一下版本说明**（README 里写了"仅在便签 16.6.22 上验证过"，
换了版本要一并更新），然后再构建。

## 2. 构建

```powershell
.\build.ps1
```

产物是 `build\NoteExport.apk`：

- 脚本自己下载缺失的构建工具到 `.buildtools/`，签名用的 keystore 也在本地生成；
- 每次都会清空 `build/classes` 再编译（否则删掉的类会留在 dex 里，见 git 历史里的那次修复）；
- 结尾会打印 `signature verified`——**没看到这行就不要发布**。

自测一下再发布：

```powershell
adb install -r build\NoteExport.apk
```

装完记得在 LSPosed 里确认模块已启用、作用域勾着「便签」，然后**冷启动一次便签应用**
（`adb shell am force-stop com.coloros.note` 再打开），否则跑的还是旧代码。

## 3. 发布一个 GitHub Release

网页操作即可：

1. 先把这一版提交推到 `main`（`git push origin main`）。
2. 打标签并推送：`git tag v1.0 && git push origin v1.0`。
   标签名用 `v` + `versionName`。
3. 打开仓库的 **Releases → Draft a new release**：
   - 选择刚推上去的标签；
   - 标题写成版本号，例如 `v1.0`；
   - 说明里写这一版改了什么（可以直接用这一段提交的说明）；
   - **把 `build\NoteExport.apk` 作为附件上传**——这一步不能省，LSPosed 的同步只认
     release 附件里的 APK，光有源码仓库不会被收录；
   - 发布。

> 发布用的 keystore 在 `.keystore/`（不进仓库）。**换机器或丢了它，签名就变了**，
> 已安装的用户无法覆盖升级，只能卸载重装。要长期发布的话，请把 keystore 与它的密码
> 自己备份好（见 `BUILD.md`）。

## 4. 上架 LSPosed 官方仓库

LSPosed 的模块列表（`modules.lsposed.org`）由官方组织
[`Xposed-Modules-Repo`](https://github.com/Xposed-Modules-Repo) 承载：每个模块一个仓库，
仓库名就是模块的包名，里面的 APK 由他们的机器人从**你的 release** 同步过去。

### 提交前的自查

| 要求 | 本项目当前状态 |
| --- | --- |
| 包名唯一、像域名倒写 | ✅ `com.qkqwork.noteexport` |
| 源码开源、可公开访问 | ✅ GPL-3.0，仓库公开 |
| APK 里带 Xposed 元数据 | ✅ `xposedmodule` / `xposedminversion=93` / `xposeddescription` / `xposedscope=com.coloros.note` |
| **有 release 且 APK 作为附件** | ⬜ 发布 `v1.0` 之后满足 |
| `versionCode` 单调递增 | ✅ 发布新版时记得 +1 |
| 一个能看懂用途的模块名与描述 | ✅ 名称 `ColorOS Note Export`；描述在清单的 `xposeddescription`（中文） |
| 只声明真正的作用域 | ✅ 只勾 `com.coloros.note` |

### 提交

1. 打开官方提交页 <https://modules.lsposed.org/submission/>（跳转到
   [`Xposed-Modules-Repo/submission`](https://github.com/Xposed-Modules-Repo/submission)
   的一个 issue 表单）。
2. 按表单填写：**模块包名**（`com.qkqwork.noteexport`）与**源码仓库地址**
   （`https://github.com/qkqwork/lsposed-coloros-note-export`）。有的版本还会要求填
   模块名、模块描述、是否已有 release——照着填即可。
3. 提交后是人工审核，可能会有人来问几个问题（作用域、为什么需要这些权限、在什么设备上验证过）。
   本项目在这些问题上是有话可说的：**零第三方依赖、只读取便签自己的数据库、不申请任何权限、
   在 PKR110 / Android 16 / 便签 16.6.22 上逐项真机验证过**。
4. 通过后官方会创建 `Xposed-Modules-Repo/com.qkqwork.noteexport`，机器人把 release 同步进去，
   模块就出现在 LSPosed 管理器的仓库列表里，可以直接安装。

### 之后怎么更新

**不需要再提交一次**：以后每次在你自己的仓库发布新的 release（`versionCode` 已 +1、
APK 作为附件），机器人会把新版本同步到官方那个仓库，用户就能在管理器里看到更新。
每个官方仓库里都有一份 `SYNC_GUIDE.md` 写着它的同步规则，第一次同步后值得读一遍。

> 上面这些是提交页与官方仓库的通行做法；**具体字段以提交表单当前显示的内容为准**——
> 表单改过版，以它为准不会错。

## 5. 用户怎么装

- 从 GitHub Releases 下 APK，自行安装（模块不申请任何权限，安装时无需授予存储权限）；
- 或在 LSPosed 管理器的模块仓库里搜索安装（上架之后）；
- 装完在 LSPosed 里启用模块、**作用域勾选「便签」**，然后冷启动一次便签应用。
  设置页顶部就写着这三步。
