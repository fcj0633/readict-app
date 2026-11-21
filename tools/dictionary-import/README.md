# Readict COL 离线工具

当前状态：M02-01读取兼容性通过；提取规则已升级`col-extract-r3`，技术质量仍阻断。支持子词条、标签和引用诊断；不是可导入的数据包生成器。convert、validate、Java导入／查询仍未实现。详细结果见[设计仓库M02记录](../../../readict-design/validation/implementation/m02/README.md)。

## 环境与运行

Node 24，ESM，锁定js-mdict 6.0.6、parse5 8.0.0；不读取数据库凭据，无浏览器或常驻服务。

```powershell
npm ci --ignore-scripts
npm test
npm run inspect -- --mdx '<本机col.mdx>' --mdd '<本机col.mdd>'
npm run diagnose -- --mdx '<本机col.mdx>' --out '../../.artifacts/dictionary/extraction-r3-new'
```

输出父目录须已存在，最终目录必须不存在，拒绝覆盖。inspect完成退出0；diagnose始终退出3（诊断未获正式发布资格）；输入／读取／文件错误退出2。输出只有诊断，不生成manifest，不操作数据库。

## 诊断契约与质量边界

schema为`readict-col-extraction-diagnostic-2`，固定`diagnosticOnly=true`、`releaseEligible=false`、审批0。JSONL文件为UTF-8无BOM、LF；单行上限512KiB，单源2MiB，RSS上限1GiB。原始全文和产物仅留忽略目录，不提交。失败保留.partial，完整结束后删除；run.json单独记录耗时和RSS。

先保留惰性AST、原始href、文本顺序和位置，再剔除脚本／资源／例句生成安全文字。位置是原HTML的UTF-16字符偏移；释义边界的局部偏移另标解码压缩body基准。Node不会执行脚本或访问链接。不能把无句号处理简化为首汉字截断；不明确的词法边界仍FAIL。

extract的regions兼容属性现在表示词条组件列表：每项有sectionOrder、componentOrder和独立entryKey。主组件0；显式子词条结构组依出现顺序编号，同major延续段同组，跨major不因同名合并。子词条不继承主词IPA或lemma；parentEntryKey仅表示来源归属。用法标签另存；仅派生说明写DERIVATION_NOTE，不生成虚构sense。未来包契约为readict-col-bundle-2，entry-v2身份加入componentOrder；本轮仅实现身份向量，不输出正式包。

源词头分类独立于未改动的col-normalize-v1；词缀／符号明确不支持，不剥掉标点冒充普通词。一个必要区域错误使整源FAIL，regions清空，quarantinedRegions只供隔离诊断。

- dispositions.jsonl：每源唯一处置、摘要、计数及问题码。
- candidates.jsonl：无源错误且依赖检查通过的候选组件；仍非发布数据。
- quarantine.jsonl：失败源的局部结果、结构证据及说明，不可混入候选。
- references.jsonl：原始目标、证据、冲突和图检查结果；区分存在、隔离、短语范围、循环、悬空及超跳。
- issues.jsonl、representative-review.jsonl、quality-report.json：问题、固定50词的可见与可用覆盖、质量摘要。present包含隔离子词条，usableExactHeadwords另列；不能把存在当成可用。

计数中sourceSucceeded包含ENTRY和ALIAS，sourceAliased另列；sourceTotal=sourceSucceeded+sourceSkipped+sourceFailed。图保留重复key及一对多，根跳转总计最多八跳。分支预算1024显式失败，目标不可用会传播隔离，不把失败依赖说成已就绪。

## 测试与限制

统一npm test执行test/*.test.mjs。夹具均为自建HTML／MDX，不提交COL原文。覆盖真实6.0.6重复key／跨块读取、结构分段、子词条、标签、身份向量、引用图、隔离及CLI。未知英文词形／括注紧接中文仍可能被隔离；某些源缺目标只能报边界错误，精确归因待补。测试通过不代表全源技术质量通过。

工具许可与数据权利分别登记。COL权利仍UNVERIFIED，快照和分发未获授权；本轮没有批准任何源修正／排除。
