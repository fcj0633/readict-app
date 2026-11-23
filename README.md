# Readict 应用工程

正式应用源码仓库。M00 已完成本机阶段验收，包含 Java 后端、React 状态页、MySQL 隔离、Flyway、MyBatis-Plus／XML、统一错误和健康检查。M01 核心账号及本地模拟验证已实现并通过本机阶段验收；真实短信／邮件通道待接入，其他业务模块尚未开发。

设计与详细记录统一放在平级设计仓库：[系统设计](../readict-design/docs/technical/architecture/Readict-V1.1-系统技术设计.md)、[M00 执行指导](../readict-design/docs/technical/implementation/M00-工程基础与本机环境执行文档.md)、[M00 验收记录](../readict-design/validation/implementation/m00/README.md)。M01 见 [执行指导](../readict-design/docs/technical/implementation/M01-用户认证与个人设置执行文档.md) 和 [验收记录](../readict-design/validation/implementation/m01/README.md)。这些文档不参与应用运行。

## 工程与环境

- `backend/`：Java 21、Spring Boot 4.1.2、MP Boot 4 Starter 3.5.18；包含迁移和正式测试。
- `frontend/`：React 19.3.1、Vite 8.3.8、TypeScript 7.0.2，版本由锁文件固定。
- `.local/`：忽略的本机配置、凭据和可选工具；`.artifacts/`：忽略的日志与临时产物。

本机使用 Windows／PowerShell 7、JDK 21、Maven 3.9、Node 24／npm 11、MySQL 8.0。标准构建和启动不需要 Python 或 Docker。应用仓库不建立 docs/；详细文档和验收证据在设计仓库，单次准备／排障脚本不纳入应用版本控制。

## 配置数据库

首次在新机器准备环境时，按 M00 执行指导第 3 章，用管理员交互登录本机 MySQL，先检查已有对象，再创建 `readict_dev`／`readict_test` 及各自 app／migrate 专用账号。不要删除重建已有库或静默重置密码；账号使用不同的秘密，不将 root 用于应用。

当前机器已完成准备，四个随机密码保存在受限、被忽略的 `.local/database.json`；其他机器需自行安全设置凭据。运行账号只有对应库的 DML 权限，迁移账号有对应库的必要 DDL 权限，数据库固定为 127.0.0.1:3306。

在仓库根目录打开 PowerShell，手动加载当前终端环境（开发用 dev，集成测试用 test）：

```powershell
$readictEnvironment = 'dev'
$env:DB_APP_USER = "readict_${readictEnvironment}_app"
$env:DB_MIGRATION_USER = "readict_${readictEnvironment}_migrate"
$readictAppSecret = Read-Host '输入运行账号密码' -AsSecureString
$readictMigrationSecret = Read-Host '输入迁移账号密码' -AsSecureString
$env:DB_APP_PASSWORD = [System.Net.NetworkCredential]::new('', $readictAppSecret).Password
$env:DB_MIGRATION_PASSWORD = [System.Net.NetworkCredential]::new('', $readictMigrationSecret).Password
$readictRateSecret = Read-Host '输入持久保存的独立限流 HMAC 密钥（至少32字符）' -AsSecureString
$env:AUTH_RATE_HMAC_KEY = [System.Net.NetworkCredential]::new('', $readictRateSecret).Password
$readictSocketDirectory = Join-Path (Get-Location).Path '.local/tmp'
New-Item -ItemType Directory -Force -Path $readictSocketDirectory | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Duser.timezone=UTC -Dfile.encoding=UTF-8 `"-Djdk.net.unixdomain.tmpdir=$readictSocketDirectory`""
```

本机曾遇到 JDK 默认临时目录下的内部套接字连接失败，上述显式临时目录设置已通过验证。开发和测试使用不同终端，用完关闭终端。`backend/.env.example` 只说明变量，Spring Boot 不自动读取 `.env`。

当前机器还保留一个不提交的便利脚本，可在仓库根目录执行 `. ./.local/Load-LocalEnvironment.ps1 dev` 或 `. ./.local/Load-LocalEnvironment.ps1 test` 替代上述手动步骤。新克隆不包含该脚本和密码文件，不能将它们视为仓库自带工具。

## 账号与本地验证配置

限流必需 `AUTH_RATE_HMAC_KEY`，缺失或少于 32 字符拒绝启动。密钥须由安全随机数生成并持久保存，不能每次启动更换。本机已生成独立的限流和验证密钥，保存在受限、忽略的 `.local/auth.json`，便利加载器会读取；新机器需自行准备。普通单测使用测试密钥。

默认 `VERIFICATION_SENDER=unavailable`，验证／恢复统一返回 503，不假装发送成功。仅本机 dev 可显式设置 `VERIFICATION_SENDER=local`、`VERIFICATION_LOCAL_ENABLED=true` 和独立的 `VERIFICATION_HMAC_KEY`（至少 32 字符）启用模拟。验证码只写入当前用户专有的 `.local/verification-outbox/`，不提供取码 HTTP 接口；随机文件名、最多 100 文件、保留 10 分钟，结束后关闭开关并清理本次文件。模拟始终显示说明，真实 verified 保持 false。真实供应商适配尚未实现。

数据库迁移由 Flyway 执行：V1 保留原样，V2 用户／设置、V3 Session JDBC、V4 挑战／恢复凭证／限流。框架不自动建 Session 表。开发启动将迁移 readict_dev，集成测试仅迁移 readict_test。

## 启动与停止

终端 A，在根目录加载开发配置后启动后端：

```powershell
Set-Location backend
mvn spring-boot:run '-Dspring-boot.run.profiles=dev'
```

终端 B，从仓库根目录启动前端：

```powershell
Set-Location frontend
npm ci
npm run dev
```

打开 [本机状态页](http://127.0.0.1:5173/)、[注册页](http://127.0.0.1:5173/register) 或 [登录页](http://127.0.0.1:5173/login)。手机号固定 +86，接受 11 位或 +86 完整号码；不支持其他地区。前后端只监听 127.0.0.1，端口为 5173／8080，占用即失败；Vite 保留 `/api` 路径代理到后端。各终端按 Ctrl+C 停止；若提示终止批处理，输入 Y。不停止共享 MySQL 或其他进程。

状态接口：`GET /api/v1/system/status`；健康检查：`GET /actuator/health/liveness`、`GET /actuator/health/readiness`。状态接口真实查询基线行；运行期依赖故障返回 503 Problem Details，启动前故障导致启动失败。认证接口位于 `/api/v1/auth`，个人设置位于 `/api/v1/me/settings`；变更接口保留 CSRF，账号／设置需要 Session 身份。注册不自动登录，退出和改密返回 204；Session 空闲 30 分钟、绝对 7 天，改密／重置使全部旧会话失效。

## 构建与测试

普通后端单测不需要数据库或凭据，在 backend/ 执行 `mvn test`。真实集成测试需在独立终端加载 test 配置，再在 backend/ 执行 `mvn -Pmysql-it verify`；只允许 readict_test，连接和迁移前校验配置，清理前复核实际目标，按精确 key 清理测试数据。

前端在 frontend/ 依次执行 `npm ci`、`npm run lint`、`npm test`、`npm run build`。详细结果和异常启动场景的历史验证见设计仓库的 M00 验收记录，本仓库不保留单次验收脚本。

迁移只由 Flyway 执行，禁 clean、自动 DDL 和 Mock 数据库回退；已执行 V1～V5 不修改，音频增量使用 V6。JAR 包含 dev／test 的隔离连接模板，凭据由环境提供，必须显式选择单一配置；测试仍仅允许 readict_test。生产部署不在当前范围。

## M02 本机词典底座

长期 Node 转换／校验工具见 [工具说明](tools/dictionary-import/README.md)。当前 COL 使用 LOCAL_MVP 有限覆盖范围，不补词；完整质量门槛仍未通过。数据权利 UNVERIFIED、快照／分发 false。详细结果见 [M02 记录](../readict-design/validation/implementation/m02/README.md)。全文包、源文件与秘密不提交。

后端提供 DictionaryProvider 内部查询，无查词 HTTP 或页面。lookup 返回 FOUND／AMBIGUOUS／NOT_FOUND／UNSUPPORTED_QUERY；未配置与依赖故障另行报错。使用精确词头和有证据的词形映射，保留独立子词条、标签及版本；POS 提示只排序，不消除歧义。切换数据集后缓存按版本隔离。

在 backend/ 执行 `mvn package`，同时生成 Web JAR 和 `readict-backend-0.0.1-SNAPSHOT-dictionary-tool.jar`。离线文件 validate 不启动 Spring 或连接数据库；其他命令按显式 dev／test 配置启动数据库／Flyway，不加载用户认证和 Session，不要求认证 HMAC。

```powershell
$readictTool = './target/readict-backend-0.0.1-SNAPSHOT-dictionary-tool.jar'
$readictBundle = '<已校验的本机完整数据包路径>'
java -jar $readictTool validate --bundle $readictBundle --allow-local-mvp
# 数据库命令前加载目标环境的专用 DB_APP_*／DB_MIGRATION_* 凭据。
java -jar $readictTool import --bundle $readictBundle --allow-local-mvp --spring.profiles.active=dev
# 中断后对完全相同的包显式增加 --resume；不修改包后覆盖续跑。
$readictDataset = (Get-Content (Join-Path $readictBundle 'manifest.json') -Raw | ConvertFrom-Json).datasetId
java -jar $readictTool inspect --dataset $readictDataset --spring.profiles.active=dev
# 从刚取得的 inspect 结果读取实际 activeVersion 后执行，禁止照抄过期版本。
java -jar $readictTool activate --dataset $readictDataset --expected-active-version <实际版本> --allow-local-mvp --spring.profiles.active=dev
```

逐条确认退出码0再继续。退出码：0成功、2输入／schema错误、3质量／使用范围未允许、4数据库／租约／冲突失败、5内部异常。导入 READY 不自动激活，切换和回退均比较活动版本。相同目标、相同预期版本的重复激活不升版。采样包、诊断包、partial目录始终不可导入；--allow-local-mvp 不能绕过其他校验。

普通测试使用自建夹具，不依赖真实词典；MySQL IT 使用 readict_test，覆盖回滚、续跑、租约、切换及缓存。真实 COL 只用于本机单独验收，不能把自建夹具通过当作真实词典质量证明。

## A02 本机音频底座

离线音频处理命令见工具说明。Web 使用私有存储环境变量 `READICT_AUDIO_STORAGE_ROOT`，test/dev 根目录名分别必须为 `readict_test`／`readict_dev`，不得放在静态资源目录。未配置时返回明确不可用，正常启动不执行导入或转码。

```powershell
# backend/ 中执行；数据库命令先加载对应单一环境凭据及私有存储根。
java -jar $readictTool audio-validate --bundle <音频包> --allow-local-subset
java -jar $readictTool audio-import --bundle <音频包> --allow-local-subset --spring.profiles.active=dev
# 中断导入显式增加 --resume；READY 不自动激活。
java -jar $readictTool audio-inspect --dataset <音频数据集UUID> --spring.profiles.active=dev
java -jar $readictTool audio-activate --dataset <音频数据集UUID> --expected-active-version <刚读取的实际版本> --allow-local-subset --spring.profiles.active=dev
```

`audio-validate` 不启动 Spring 或数据库。其余音频子命令不加载 MVC、Session 或账号后台任务，不需要认证 HMAC。默认完整范围校验；`--allow-local-subset` 仅允许有明细的本机缺陷隔离策略。退出码沿用词典工具：0成功、2输入、3质量／策略、4数据库／租约／版本冲突、5内部错误。失败保留旧活动版本，回退使用同样的版本条件切换，不自动删除旧 READY 媒体。

`AudioCatalog.find` 是独立内部接口，按实际展示词头返回录音、口音及音频版本，不改变 COL 音标或文字查询结果。鉴权播放为 `GET/HEAD /api/v1/dictionary/audio/{audioId}`，支持单段 Range、ETag 和 If-Range；认证检查先于条件请求。未登录401、非法标识400、不可访问资源404、未配置或读取故障503。音频目录和导入不提供 HTTP 入口。正式卡片播放待 M05；机器解码和浏览器媒体事件不替代人工试听。数据权利仍 UNVERIFIED，不允许快照或分发。

本轮按 MVP 使用：导入冻结清单，媒体逐文件核验后直接发布至私有目录，READY 复用发布结果并核对数据库计数；启用复用 READY 校验结果并检查计数及活动版本，播放时校验实际摘要。完整复验、存储巡检和断电恢复后置，未声称全面验收；独立 `audio-validate` 仍可按需执行完整媒体校验。

## M03 本机书籍导入与书架

范围：私有存储、有界上传、EPUB 解析与清洗、确定性阅读产物与文本索引、导入任务、书架 API 与前端书架页。M04 的阅读器与进度、M05 查词、M06 收藏、M07 翻译、M08 清理巡检、M09 阶段验收仍未开发。

环境：书籍私有根目录用 `READICT_BOOK_STORAGE_ROOT`，末级目录名必须为 `readict_test`／`readict_dev`，未配置时书籍接口返回 503，其他模块不受影响。上传上限 25 MiB；`spring.servlet.multipart` 已在 `application.yml` 设为 26 MB／28 MB 并由业务再校验。V7 已在 `readict_test` 与 `readict_dev` 执行（开发库于 2026-11-23 经用户批准执行：首次迁移 1 个、二次启动 0 个，既有词典／音频计数不变）。

```powershell
# backend/：普通单测与真实 MySQL 集成。集成前加载 test 单一环境凭据并设置私有存储根。
$env:READICT_BOOK_STORAGE_ROOT = '<仓库>\.local\tmp\it-books\readict_test'
mvn test
mvn -Pmysql-it verify
# frontend/：npm ci、lint、test、build
```

接口（均需 Session；变更需 CSRF；上传与删除需 `Idempotency-Key`）：`GET /api/v1/books`、`POST /api/v1/books/imports`、`GET /api/v1/books/imports/{id}`、`GET /api/v1/books/{id}`、`DELETE /api/v1/books/{id}`、`GET /api/v1/books/{id}/cover`、`GET /api/v1/books/{id}/reader-manifest`、`GET /api/v1/books/{id}/artifacts/{artifactId}/epub`、`GET .../text-index?spineHref=`。

不变量：READY 必须对应已发布且摘要一致的产物；同源文件重复导入复用书籍；跨用户资源一律 404；产物无脚本、无外部引用；同一源文件两次处理 `artifactHash` 一致；删除只置 DELETING 并登记待清理，物理回收留给 M08。

限制与未验收项：前端书架页已用 Edge（headless）完成一次自动截图检查（空态、导入进度、完成态、删除确认、390px 单列，截图见实施记录），人工确认仍建议；A03（无封面/无目录）、A05（条目与总量预算）、A07（DTD/XXE）、A11（崩溃接管）、A12（发布后 DB 失败）已有单测或集成覆盖；真实 EPUB、V03 全量恶意矩阵与 M08 清理执行仍待办，详见设计仓库 [M03 实施记录](../readict-design/validation/implementation/m03/2026-11-23-local/README.md)。`artifactId` 下载不支持 Range；阅读器未接入，书架“打开”按钮为禁用占位。
## M04 本机阅读内核与进度

范围：`/reader/:bookId` 阅读页、epub.js 单页分页、CFI＋quote 恢复、进度保存与冲突、V8 `reading_progress`、`GET/PUT /api/v1/books/{id}/progress` 与书架进度联动。M05 取词、M06 收藏、M07 翻译仍未开发。

环境：V8 已在 `readict_test` 执行；**`readict_dev` 未迁移**，需批准后单独执行。前端依赖 `epubjs@0.3.93`（BSD-2-Clause，包自带 TypeScript 类型），执行 `npm ci` 后生效。

接口：`GET /api/v1/books/{id}/progress` 返回 `{progress:null|...}`；`PUT` body 为 `{expectedVersion,requestId,anchor}`，需 CSRF，成功返回最新进度；409 `PROGRESS_CONFLICT` 附带 `currentVersion` 与 `serverProgress`；422 `ANCHOR_INVALID`；410 `ARTIFACT_REVOKED`。

行为：打开前校验 `artifactHash`（WebCrypto）；恢复顺序为 CFI＋quote 校验 → 同章唯一 quote 回退 → 章节起点并提示，降级不写回旧进度；进度保存 1s 防抖、最小间隔 5s、单标签串行；冲突停止自动覆盖；断网保留本地待同步并在下次打开时补交；删除书时同事务删除进度。

验证：`mvn test` 102 通过；`mvn -Pmysql-it verify` 56 通过（新增 `ReadingProgressIT` 4 项，V8 首次迁移成功、二次启动 0 迁移）；`npm run lint` 0 警告、`npm test` 39 通过、`npm run build` 通过；Edge headless 实跑阅读页（打开、翻页、字号 24、刷新恢复、390px 无横向溢出）见设计仓库 [M04 实施记录](../readict-design/validation/implementation/m04/2026-11-23-local/README.md)。

限制：仅对 Edge/Chromium 给出证据，Firefox/Safari 明确暂不支持；真实大书、跨版式精确恢复、内存／Blob 长期观察未验证；阅读页右栏“收藏”为 M06 前禁用占位；产物下载不支持 Range；阅读页不套用账号页布局。
