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

迁移只由 Flyway 执行，禁 clean、自动 DDL 和 Mock 数据库回退；已执行 V1 不修改，后续新增 V2 等迁移。正式 jar 仅含 dev 配置，test 配置只在测试 classpath。生产部署不在当前范围。

## M02 词典工具当前进度

长期离线工具位于 [tools/dictionary-import](tools/dictionary-import/README.md)。已验证 js-mdict 6.0.6 对实际 COL 的全源枚举，并提供r3结构提取诊断、子词条身份、标签和引用检查；57项正式自建夹具测试通过，两次全源诊断一致。当前真实提取质量未通过，M02 仍为部分实施；尚无正式 convert／validate 包、V5、Java 导入工具或 DictionaryProvider。源文件和诊断全文不提交，无需数据库配置即可运行 Node 工具。结果及阻断见 [M02 记录](../readict-design/validation/implementation/m02/README.md)。
