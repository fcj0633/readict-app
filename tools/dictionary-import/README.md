# Readict COL 离线工具

当前状态：M02-01 输入与解析器兼容性已验证；M02-02 提取诊断已实现但未通过真实全量质量门槛。**不是可导入的数据集生成器**；convert、validate、Java 导入／查询尚未交付。实际证据见[设计仓库记录](../../../readict-design/validation/implementation/m02/README.md)。

## 环境与运行

Node 24，精确锁定 js-mdict 6.0.6、parse5 8.0.0，ESM，无数据库、浏览器或常驻服务。Node 工具不读取数据库凭据。

在本目录执行：

```powershell
npm ci --ignore-scripts
npm test
npm run inspect -- --mdx 'D:\Java-learning\Readict\readict-design\dict\col\col.mdx' --mdd 'D:\Java-learning\Readict\readict-design\dict\col\col.mdd'
npm run diagnose -- --mdx 'D:\Java-learning\Readict\readict-design\dict\col\col.mdx' --out '../../.artifacts/dictionary/extraction-r2'
```

输入通过参数指定，不复制源文件到工程。输出目录的父目录应已存在，最终输出目录必须不存在；禁止覆盖已有结果。inspect 输出原始文件摘要、源顺序枚举计数、重复 key 的位置／记录摘要、资源名称和运行预算；不会倾倒正文。diagnose 输出每条处置、问题、固定50词的候选提取结果及质量报告，只有诊断能力，退出码始终为 **3（未通过正式质量门槛）**。输入／解析／文件失败退出 **2**；inspect 完成退出 **0**。不能把依赖安装或 inspect 成功当词典已验收。

诊断文件均属于本地敏感数据，放在忽略的 `.artifacts/`，不提交；`representative-review.jsonl` 含候选文字，只供本机核对。报告始终标记 `diagnosticOnly=true`、`releaseEligible=false`、人工审核0，不生成可冒充正式包的 manifest。失败保留 `.partial`；完整诊断完成后删除该标记。没有修改源文件，也不写数据库。

## 解析与提取边界

6.0.6 会无条件重排 `keywordList`，适配器重新按物理 key-block 顺序枚举，按原始位置计算记录边界。逐完整 record-block 读取后拼接跨块记录，不按 key 查找覆盖重复项；严格解码，检查索引总数、范围、块长度和单记录2MiB上限。包自带工具许可为 MIT；词典数据权利仍 UNVERIFIED，快照和分发均未获确认。

`col-extract-r2` 使用惰性 DOM，识别 h4/span.title 和各 wt-container 区域，剔除脚本、资源和例句；支持独立中文翻译节点、中文段落和有完整英文句结束分隔的 COL 版式。保留括注、词内英文和 Unicode IPA；只读交叉引用不冒充释义。未支持的中英边界、缺字、词头／义项结构进入 FAIL，不按首个汉字截断、不补写源缺字。一个区域失败使整个源记录失败，候选不进入正式结果。**规则覆盖仍不完整**，不能据初步成功数量宣称完整词典。

src/normalize.mjs 是提取时的词头比较键辅助实现；尚未完成 Java 黄金向量和正式词形关系模块。test/ 内的 HTML／MDX 夹具均为自建，不含 COL 词典正文。测试包括实际6.0.6读取自建重复key／跨块UTF-8记录、CLI失败码、目录不可覆盖、DOM隔离与Unicode规则。
