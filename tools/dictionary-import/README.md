# COL 离线转换工具

Node 24，ESM，js-mdict 6.0.6、parse5 8.0.0。当前规则 col-extract-r4，转换器 readict-col-converter-4。正式convert入口固定V8堆上限512MiB；直接运行CLI时同样使用 `node --max-old-space-size=512 src/cli.mjs convert ...`，RSS总预算仍为1GiB。运行前在本目录执行 `npm ci`；正式自建夹具测试入口为 `npm test`。

```powershell
npm run inspect -- --mdx <源MDX> --mdd <源MDD>
npm run diagnose -- --mdx <源MDX> --out <新诊断目录>
npm run convert -- --mdx <源MDX> --mdd <源MDD> --out <新数据包目录> --dataset-version col-local-mvp-1
npm run validate -- --bundle <数据包目录> --allow-local-mvp
```

尖括号是路径占位，需要替换。输出目录不得存在；失败的 `.partial` 目录不能导入，不能直接改名伪装完成。完整转换同时在包外保留同名 `.diagnostic` 目录，不提交全文或诊断内容。诊断退出3；输入／文件错误退出2；成功校验退出0。

LOCAL_MVP 使用当前 COL 可安全提取的子集，保留全源处置和错误清单；不补词、不补写原文。必要区域错误仍整源隔离。see also／compare 非根附加目标失效只记警告，不连带删除独立释义；跳转／必要关系维持严格检查。完整 source pattern 列表和明确说明可以生成带证据的映射，不根据索引差异、删词尾或相邻词猜原型。

正式包为 readict-col-bundle-2，诊断仍为 readict-col-extraction-diagnostic-2。诊断固定 diagnosticOnly=true、releaseEligible=false，永远不可导入。LOCAL_MVP 正式包 diagnosticOnly=false，但完整质量 releaseEligible 仍为 false；显式 --allow-local-mvp 只允许采用该范围，不绕过摘要、身份、计数、引用、安全及预算检查。localMvpEligible 表示机器校验范围；源文对照抽查结果另归设计仓库，不等于全词典准确率。

身份采用 entry-v2／sense-v1、规范化 col-normalize-v1。主组件为0，子组件独立，不继承主词 IPA 或 lemma；保留标签、说明、引用和 UTF-16 源位置。UTF-8 无BOM、LF；单源2MiB、单行512KiB、Node RSS目标1GiB、完整包1GiB。脚本、远程资源和例句子树不执行／不进入释义。

原始词典、产物与秘密仅留本机忽略目录。软件许可和词典数据权利分别记录；当前 UNVERIFIED，快照及分发 false。Java 导入与查询运行方式见应用根 README；详细设计和验收见平级设计仓库 M02 记录。

## 独立发音库

音频与 COL 文字包独立。使用同一工具、`sound-map-v3` 和 `readict-audio-converter-4`，不在线补录或猜测原型。配置 JSON 仅存本机，字段为 `mdx`、`mdd`、`icons`、`ffmpeg`、`ffprobe` 的绝对路径；分别指向 Sound.mdx、Sound_us_uk.mdd、sound.mdd 和便携工具。不得提交源文件、工具二进制、私有音频或配置。

```powershell
npm run audio:inspect -- --config <本机配置JSON> --out <全新检查目录>
npm run audio:convert -- --config <本机配置JSON> --inspection <检查目录> --out <全新音频包目录> --allow-local-subset
# 中断后保留同一输入、配置、检查目录和输出参数，并显式增加 --resume。
npm run audio:validate -- --bundle <完成的音频包目录> --allow-local-subset
```

`--allow-local-subset` 采用用户允许的本机可用录音子集。只允许有明确摘要与位置的源格式、PCM、信号或转码质量缺陷隔离；进程失败、超时、路径、完整性和计数错误仍阻断。不放宽每条录音门槛，映射不返回隔离资源。默认校验完整范围，LOCAL_MVP 包必须显式允许。技术可用、完整音源、人工试听和数据权利分别记录；本机技术可用不代表发布授权。

包为 `readict-audio-bundle-1`，包含 manifest、assets、mappings、issues、quality、dispositions 与摘要寻址 media。临时 `.partial` 永远不可导入；成功后 checkpoint 保留在包外用于核查。两路有界 FFmpeg 进程，各次调用显式独立映射输入与输出，不拼接录音；每次调用30秒超时，失败回到单资源诊断。每条源和目标均解码检查信号、时长及格式。统一 `npm test` 同时执行文字与音频测试；合成媒体字节夹具只验证传输与身份，不作为实际播放或人工试听证据。
