# OPPO/ColorOS 便签批量导出 · Xposed 模块

Hook `com.coloros.note`，在**便签进程内部**批量导出全部便签：

- **图片**：每条便签渲染成一张长图 PNG（按分类分文件夹）
- **Word**：两种组织方式，可在设置页自选
  - `单个 docx`：一个 .docx 内含全部分类，图片内嵌
  - `每条一个 docx`：按分类分文件夹，每条便签一个 .docx

产物落在 `Download/便签导出/<时间戳>/` 下，**不需要任何存储权限**。

针对 **ColorOS 16（Android 16 / API 36）· 便签 16.x**。

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
3. 打开模块"便签批量导出"，选格式与组织方式，点"开始导出"。
4. 结果在 `Download/便签导出/`。

## 已知限制

- **加密便签不导出**（正文在应用内加密，模块只统计条数）。
- `com.oneplus.provider.Note` 与 provider 类的对应关系需在真机核实；
  查不到时会自动回退到"待导出标记"链路。
- 便签升级若改动表名/列名，模块会记录诊断日志并尽量退化查询，不会静默导出空内容。
