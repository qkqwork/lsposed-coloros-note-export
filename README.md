# lsposed-coloros-note-export

**LSPosed 模块：批量导出 ColorOS / OPPO 便签为 Word 文档或原版长图**

> 非官方项目，与 OPPO / ColorOS 无关。
> 仅在 ColorOS 16（Android 16 / API 36）· 便签 16.6.22 上验证过。

## 免责声明（先读这个）

- **本项目由 DeepSeek Harness（AI 编程代理）编写**；仓库 owner **qkqwork** 提供设备、提出需求并做真机验证。
- **qkqwork 不对本项目负责**：不对代码的正确性、稳定性、安全性作任何担保，也不对使用它产生的任何后果负责
  ——包括但不限于便签内容损坏或丢失、便签应用异常、系统不稳定、账号或服务条款风险。
- 模块在**便签应用进程内**运行：读取它的数据库、驱动它的界面、把便签内容写到 `Download/便签导出/`。
  请自行评估风险后再安装，**安装前务必备份**，重要内容不要只留一份。
- 代码由 AI 生成，可能有错误或过时之处；源码全部在本仓库，欢迎自行审查后再使用。
- 非官方项目，与 OPPO / ColorOS / OnePlus 均无关联。

> This project was written by **DeepSeek Harness**, an AI coding agent. The
> repository owner, **qkqwork**, provides the device and does the real-device
> testing, and **takes no responsibility** for the code or for anything that
> results from using it. It runs inside the Notes app, reads its database and
> writes your notes out to `Download/便签导出/` — review the source and back up
> your data before installing. Not affiliated with OPPO, ColorOS or OnePlus.


Hook `com.coloros.note`，在**便签进程内部**批量导出全部便签：

- **图片（原版长图）**：模块调用便签自己的「分享为图片」入口（`doPictureShare`），
  **渲染、量高度、分页全部由便签自己的编辑器 WebView 完成**（每页 2528 px，页位图由应用写到
  `…/capture/captureN.png`）；模块只做最后一步——把应用吐出的页面**按顺序摞成一张长图**、
  铺上便签应有的底色、裁掉尾部空白。
  **像素全部来自便签应用，模块不重绘任何内容**：同一套渲染器，所以画面与应用自己导出的长图一致，
  差别只在装帧——我们保留编辑器原始宽度（1264 px）、不套它的分享卡片边距（它成品是 1094 px）、
  也不带它的角标。
- **图片（模块绘制）**：不依赖 WebView 的自绘版式（`LongImageRenderer`），观感与应用不同，
  适合导出时会话里 WebView 起不来的场景
- **Word**：两种组织方式，可在设置页自选
  - `单个 docx`：一个 .docx 内含全部分类，图片内嵌
  - `每条一个 docx`：按分类分文件夹，每条便签一个 .docx
- **分享长图的水印**（在便签应用里生效，与导出产物无关）：便签自己「分享为图片」时底部那行
  ColorOS 水印，可选 **去掉（默认）/ 去掉文字但保留原高度（留白）/ 换成我自己的文字 / 不动它**。
  水印是应用自己布局的一部分，只能在它画图的过程中处理，所以这一项是 hook 而不是后期裁剪；
  自定义文字保存在设置里，切回该模式仍然在
- **选择要导出哪些便签**：设置页的「选择便签…」会向便签应用要来一份清单（标题 · 分类 · 字数，
  加密与回收站中的便签会标出来），勾选后只导出这些；**一条都不勾 = 全部**。选择会被记住，
  下次导出同一批不用重新勾
- **先试 1 条 + 预览**：先只跑第 1 条（不记住条数），导出的第一张图的**顶部**会显示在设置页上，
  用来核对底色、字色与版式——整张长图太长，只留顶部一截，否则一个 binder 回包放不下
- **长图底色跟随系统主题**：便签自己就是按系统主题画长图的（深色主题下交的是透明纸 + 白字，
  浅色主题下直接把白纸黑字画进图里），模块跟着同一套配色补底色，**不重绘任何像素**。
  设置页会把当前主题对应的结果写出来；想换底色，改系统深色模式即可
- **打开导出目录 / 恢复默认设置**：前者直接跳到 `Download/便签导出/`，后者一键回到推荐选项
- **导出进度条 + 取消导出**：导出跑在便签进程里，模块自己的进程看不到，所以便签那一侧把进度
  （第几条 / 共几条 / 正在画哪一条）与"是否请求取消"放在它自己的内存里，设置页每 0.7 秒问一次
  并画进度条；「取消导出」是请求而不是硬停——正在画的那一条会画完，从下一条起不再处理
- **选项即改即存**：任何一处改动立刻写入，任务被划掉或进程被杀也不会丢

产物落在 `Download/便签导出/<时间戳>/` 下，**不需要任何存储权限**。

已装模块在 LSPosed 列表里显示的名字是 **ColorOS Note Export**（`res/values/strings.xml` 的
`app_name`），仓库名与模块名是两回事，改仓库名不影响已装机器的行为。

## 为什么这样实现

便签的数据无法从模块自己的进程读取（私有目录），所以本模块走"注入便签进程"路线，
但**不去逆推应用的富文本模型**，而是直接读它自己的 SQLite 数据库：

| 项目 | 值 |
| --- | --- |
| 数据库 | `/data/data/com.coloros.note/databases/nearme_note.db` |
| 便签表 | `rich_notes` |
| 分类表 | `folders`（`guid` ↔ `name`） |
| 正文（纯文本） | `rich_notes.text` |
| 正文（富文本） | `rich_notes.raw_text` ← **本身就是 HTML** |
| 附件 | `/data/data/com.coloros.note/files/<local_id>/` |
| 回收站标记 | `rich_notes.state = 2` |

`raw_text` 是 HTML 这一点决定了整个方案的可行性：Word 生成只需做
**HTML → WordprocessingML(OOXML)** 转换，图片生成只需把同一份 HTML 丢给 WebView 渲染。
不需要理解便签的任何内部类。

## 触发链路

模块设置页查询便签应用**自己导出的、免权限的 provider**，查询动作会顺带冷启动便签进程：

```
content://com.oneplus.provider.Note/<自定义路径段>
        ↓  被模块 hook 拦截，param.setResult(...)
便签进程内执行导出 → 结果以 MatrixCursor 回传
```

模块 hook 的是便签的 `NoteBackupRestoreProvider.query()`，用一个自造路径段当哨兵，
并通过 `Binder.getCallingUid()` 校验调用方只能是自己，避免任何第三方应用借道导出用户便签。

备用链路：设置页写入"待导出"标记 → 拉起便签 → 便签启动时
（hook `Instrumentation.callApplicationOnCreate`）读取标记并在后台线程执行导出。

## 目录

```
src/com/qkqwork/noteexport/
  Main.java                  Xposed 入口：provider 拦截（导出 / 探针 / 诊断 / 便签清单）+ 兜底链路
  Hooks.java                 反射式 findAndHookMethod（绕开 legacy API 的签名坑）
  ConfigActivity.java        设置页（XML 布局 + 资源，选项即改即存、进度条、预览）
  PickerActivity.java        便签选择器（向便签应用要清单，勾选后只导出这些）
  Progress.java              导出进度与取消请求（便签进程里的状态，设置页轮询它）
  Thumbnail.java             导出结果顶部的小预览图（骑着回答的 cursor 回传）
  ExportOptions.java         导出选项（格式 / 组织方式 / 底色 / 便签子集 / 限制条数…）
  ExportRequest.java         选项在进程间的载体（查询串，附带落盘兜底）
  ConfigProvider.java        模块自己的只读设置 provider
  ConfigContract.java        两边共享的常量（路径段、列名、取值）
  NoteSelection.java         勾选的便签（存 id，空 = 全部）
  NoteExporter.java          数据库读取 + 遍历编排（三种格式的入口与便签子集过滤）
  NativeBatchExport.java     原版长图：驱动便签自己的 doPictureShare，摞页面、补底色、裁白
  NativeImageExport.java     原版长图的逐条路径
  LongImageRenderer.java     模块自绘长图（HTML → WebView → PNG）
  NoteStore.java             便签数据库访问（含旧版本列数退化）
  Note.java / Doc.java       数据模型与 OOXML 片段
  HtmlToWord.java            HTML → WordprocessingML 转换器
  DocxWriter.java            .docx 打包（含图片内嵌）
  NoteHtml.java              便签 raw_text 的整理
  ExportSink.java            落盘（MediaStore，免存储权限；同名覆写）
  ProgressNotifier.java      便签应用自己的通知栏进度
  WatermarkHook.java         分享长图水印的四种处理
  WatermarkSettings.java     水印设置（provider + 镜像文件）
  Diagnostics.java           真机诊断报告
  CaptureProbe / PipelineProbe / ShareProbe.java   调查用的探针（debug=1 时才挂）
build.ps1                    javac + d8 + aapt2 + apksigner（不依赖 Gradle）
build/*.py, build/*.sh       调查便签应用与整理产物用的工具脚本
```

## 构建

```
build.ps1        # Windows PowerShell，无需 Gradle、无需 Android Studio
```

需要 **JDK 17**；Android SDK 不必预装——脚本自己下载缺失的
`android.jar`（platform 35）与 build-tools（aapt2 / d8 / zipalign / apksigner）到 `.buildtools/`，
签名用的 keystore 也在本地生成。链路是：

```
aapt2 compile/link  →  javac（stubs + src）  →  d8  →  写入 APK  →  zipalign  →  apksigner
```

资源先链接、再编译 Java：这样 `R.java` 在 `javac` 之前就存在。
产物是 `build/NoteExport.apk`（约 300 KB，零第三方依赖，dex 只含模块自己的类）。

`build/` 下的 Python / shell 脚本是调查便签应用时用的工具（找方法名、跑管道、清理产物），
它们从你自己的便签应用里生成需要的清单，仓库里不附带任何反编译产物。

## 安装与使用

1. 安装 APK，在 LSPosed 中启用模块，**作用域勾选「便签」**。
2. 冷启动一次便签（让模块注入）。
3. 打开模块「ColorOS Note Export」，选格式与组织方式；需要的话点「选择便签…」勾一批便签。
4. 建议先点「先试 1 条」，在设置页上核对预览图；满意后点「开始导出」。
5. 结果在 `Download/便签导出/`（可在设置页点「打开导出目录」直达）。

长图底色由系统主题决定：想让产物是黑底白字，就把手机切到深色模式；浅色模式下便签交出的
就是白纸黑字的成品图，模块无从改成黑底。

## 已知限制

- **加密便签不导出**（正文在应用内加密，模块只统计条数，选择器里会标注并禁止勾选）。
- **长图底色不能单独切换**：底色跟随系统主题。便签按主题决定自己画什么——深色主题交的是
  透明纸 + 白字（模块补底色），浅色主题直接把白纸黑字画进图里（底色是图的一部分，改不了）。
  强行反色只能靠逐像素重绘字色，抗锯齿的字边、照片里的大片同色区域和超过 40 像素的连续
  平直笔画会留下残影，所以这个选项被去掉了。
- 预览只有**第一张图的顶部**，用来判断配色与版式，不是完整长图。
- `com.oneplus.provider.Note` 与 provider 类的对应关系需在真机核实；
  查不到时会自动回退到"待导出标记"链路。
- 便签升级若改动表名/列名，模块会记录诊断日志并尽量退化查询，不会静默导出空内容。

## 致谢与参考

**水印**这一块的思路来自 [araea/note-watermark](https://github.com/araea/note-watermark)
（模块名「素笺」，双许可 Apache-2.0 / MIT）：

- 三种处理方式（不显示 / 留白 / 自定义文字）和"在应用画图的过程中动手、而不是事后裁剪"这个方向，
  是它先做的（本项目在自己的四种模式里多了一个"不动它"）；
- 它也记录了那批**属于便签应用自己**的视图名（`color_os_logo.xml` 里的
  `mLogoLinearLayout` / `mLine` / `mWaterMark` / `mShareLogo` / `mShareLogoOriginal`），
  本项目按这些名字在本机便签 16.6.22 的 dex 里逐个核对后自行实现——先核对名字，再写代码；
- 兜底设置文件名 `note_watermark.txt` 沿用了同一约定。

**本项目没有复制它的代码**：它用 Gradle + libxposed API 102 构建，本项目用无 Gradle 的自建编译链
（`javac`/`d8`/`aapt2`/`apksigner`）+ legacy Xposed API 手写桩；两边源码互查也没有对方的包名、
类名或注释。相同的是**思路与目标**，以及便签应用自身的标识符——那些名字属于便签应用，不属于任何一个模块。

## 依赖与许可

- **许可**：GPL-3.0（见 `LICENSE`）。分发本模块或其修改版时，需一并提供完整源码。
- **第三方代码**：没有。全部源码只 import `android.*` / `java.*` / `javax.*` 与
  Xposed API；Word（OOXML）与长图渲染都是本项目自己实现的，未使用任何第三方库。
- **Xposed API**：`stubs/` 下的 `de.robv.android.xposed.*` 是**手写的编译期桩**
  （真类由 LSPosed 在运行时提供，构建脚本会把该包从 dex 里剥离），不是把框架代码打包进来。
- **不捆绑目标应用**：仓库里没有 `com.coloros.note` 的 APK、dex、资源或字体；
  调查便签内部流程用的反汇编摘录（`build/*.txt`）也**不随仓库发布**，
  旁边的脚本可在本地对你自己的应用副本重新生成。
- **非官方**：与 OPPO / ColorOS 无任何关联；名称中的 ColorOS 仅用于说明适用对象，
  本项目未使用其图标或其它品牌资源。
