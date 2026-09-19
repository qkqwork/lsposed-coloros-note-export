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

### 提交表单里的「描述」写什么

三档长度都放在这里，按表单实际字段挑一个填；**模块列表与 LSPosed 管理器里显示的那一句**
来自 APK 的 `xposeddescription`（在 `AndroidManifest.xml` 里），改它需要重新构建发布。

**一句话（列表/About 用，约 40 字）**

> 在便签进程内批量导出 ColorOS / OPPO 便签为 Word 或原版长图，可挑选便签、看进度并可取消，
> 另有一键整本备份（zip）。

**一段话（提交表单的长描述，中文）**

> LSPosed 模块，作用域只勾「便签」。它在便签应用自己的进程内运行，读取它的数据库与渲染链路，
> 把便签批量导出成 Word 文档或原版长图：原版长图由便签自己的「分享为图片」渲染与分页，模块只把
> 它的页面摞成长图并补底色，所以画面与便签里看到的一致。可以按标题/分类挑选要导出的便签、看到逐条
> 进度与预计剩余时间、随时取消；导出产物落在 `Download/便签导出/`，**不需要任何存储权限**，
> 也不含任何第三方库（不引入 AndroidX/Material）。另有一个「整本备份」按钮，把便签的数据库与附件
> 打包成一个 zip（同样不需要 root）。
>
> 导出用：Word、长图阅读/打印；备份用：换机与误删的兜底。加密便签不会被导出，回收站中的可自选。
> 只在 ColorOS 16 / Android 16 / 便签 16.6.22 上做过逐项真机验证，其他机型与版本请自行测试。

**English（一段话，给英文页面用）**

> An LSPosed module for ColorOS / OPPO Notes (scope: the Notes app only). It runs inside the Notes
> process, reads the app's own database and capture pipeline, and exports every note — or just the
> ones you tick in its picker — as a Word document or as the app's own long picture, rendered by the
> Notes app itself so the result matches what you see in it. Exports land in `Download/便签导出/`
> and need no storage permission; the module carries no third-party code at all. A second button packs
> the whole notebook — database and attachments — into a zip, with no root required. Verified on
> ColorOS 16 / Android 16 / Notes 16.6.22 only; other devices and versions are untested.

**提交 issue 里通常要填的几项**（顺序与字段名以表单当前显示为准）

| 字段 | 填什么 |
| --- | --- |
| 模块名称 | `ColorOS Note Export` |
| 模块包名 | `io.github.qkqwork.noteexport` |
| 源码仓库 | `https://github.com/qkqwork/lsposed-coloros-note-export` |
| 模块描述 | 上面「一段话」那一版 |
| 作用域 | `com.coloros.note`（便签） |
| 许可 | GPL-3.0 |
| 是否开源 | 是 |
| 已测试环境 | ColorOS 16（Android 16 / API 36）· 便签 16.6.22 |

### 提交前的自查

| 要求 | 本项目当前状态 |
| --- | --- |
| 包名唯一、像域名倒写 | ✅ `io.github.qkqwork.noteexport` |
| 源码开源、可公开访问 | ✅ GPL-3.0，仓库公开 |
| APK 里带 Xposed 元数据 | ✅ `xposedmodule` / `xposedminversion=93` / `xposeddescription` / `xposedscope=com.coloros.note` |
| **有 release 且 APK 作为附件** | ⬜ 发布 `v1.0` 之后满足 |
| `versionCode` 单调递增 | ✅ 发布新版时记得 +1 |
| 一个能看懂用途的模块名与描述 | ✅ 名称 `ColorOS Note Export`；描述在清单的 `xposeddescription`（中文） |
| 只声明真正的作用域 | ✅ 只勾 `com.coloros.note` |

### 提交

**前提：先有 release。** 机器人只从 release 的附件里取 APK，没有 release 的仓库会被直接打回，
所以第 3 步（发 `v1.0`）做完再交。

1. 打开官方提交页 <https://modules.lsposed.org/submission/>。
   它会用 **GitHub 账号登录**（授权后以你的名义建 issue），所以用**仓库所属的那个账号**登录。
2. 表单基本只要两样：**模块包名** `io.github.qkqwork.noteexport` 与
   **源码仓库** `https://github.com/qkqwork/lsposed-coloros-note-export`；
   有的版本还会要模块名、描述、是否已有 release——描述直接用本文上面那三档里的「一段话」。
3. 提交后会在 [`Xposed-Modules-Repo/submission`](https://github.com/Xposed-Modules-Repo/submission)
   里生成一个 issue，等维护者人工过。可能会被问作用域/权限/验证环境，本项目的现成答案：
   **不申请任何权限、只读便签自己的数据库、零第三方依赖（不含 AndroidX/Material）、
   在 PKR110 / Android 16 / 便签 16.6.22 上逐项真机验证过**。
4. 通过后官方会建 `Xposed-Modules-Repo/io.github.qkqwork.noteexport`，机器人把最新 release 同步进去
   （APK + 从清单读到的名称/描述/版本），模块就会出现在 LSPosed 管理器的模块仓库里。

**表单打不开 / 提交失败时的备用做法**：直接在
<https://github.com/Xposed-Modules-Repo/submission/issues/new/choose> 选模块提交的模板，
把下面这段填进去提交（字段名照模板，内容照抄）：

```
模块名称：ColorOS Note Export
模块包名：io.github.qkqwork.noteexport
源码仓库：https://github.com/qkqwork/lsposed-coloros-note-export
License：GPL-3.0（开源）
作用域：com.coloros.note（便签）
模块描述：<粘贴本文「一段话」那一版>
已发布版本：v1.0（APK 在 release 附件里）
已验证环境：ColorOS 16 / Android 16（API 36）/ 便签 16.6.22
```

**常被打回的原因**（对照自查，本项目都已满足）：仓库私有或没有 LICENSE；
release 里没有 APK 附件；清单里缺 `xposedmodule` / `xposedminversion`；
作用域写得比实际需要大；描述看不出这个模块到底干什么。

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
