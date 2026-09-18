# lsposed-coloros-note-export

**LSPosed 模块：批量导出 ColorOS / OPPO 便签为 Word 文档或原版长图**

> 非官方项目，与 OPPO / ColorOS 无关。
> 仅在 OnePlus PKR110 · ColorOS 16（Android 16 / API 36）· 便签 16.6.22 上验证过。

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
src/com/dsh/noteexport/
  Main.java                  Xposed 入口：4 个 hook
  NoteExporter.java          数据库读取 + 遍历 + 落盘编排
  Note.java                  便签数据模型
  NoteStore.java             便签数据库访问（含旧版本列数退化）
  HtmlToWord.java            HTML → OOXML 转换器
  DocxWriter.java            .docx 打包（含图片内嵌）
  LongImageRenderer.java     HTML → 长图 PNG
  ExportOptions.java         导出选项
  ConfigActivity.java        设置 / 一键导出界面
  ConfigProvider.java        只读设置 provider
  ConfigContract.java        共享常量
build.sh                     javac + d8 + aapt2 + apksigner（不依赖 Gradle）
```

## 构建

```
build.sh          # Windows: bash build.sh 或见 BUILD.md
```

只需 JDK 17 + 一份 android.jar（脚本会自动下载缺失的构建工具到 `libs/`）。

## 安装与使用

1. 安装 APK，在 LSPosed 中启用模块，**作用域勾选「便签」**。
2. 冷启动一次便签（让模块注入）。
3. 打开模块"ColorOS Note Export"，选格式与组织方式，点"开始导出"。
4. 结果在 `Download/便签导出/`。

## 已知限制

- **加密便签不导出**（正文在应用内加密，模块只统计条数）。
- `com.oneplus.provider.Note` 与 provider 类的对应关系需在真机核实；
  查不到时会自动回退到"待导出标记"链路。
- 便签升级若改动表名/列名，模块会记录诊断日志并尽量退化查询，不会静默导出空内容。

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
