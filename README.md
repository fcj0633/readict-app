# Readict 应用工程

正式应用源码仓库。M00 已完成本机阶段验收，包含 Java 后端、React 状态页、MySQL 隔离、Flyway、MyBatis-Plus／XML、统一错误和健康检查。用户认证与其他业务模块尚未开发。

设计与详细记录统一放在平级设计仓库：[系统设计](../readict-design/docs/technical/architecture/Readict-V1.1-系统技术设计.md)、[M00 执行指导](../readict-design/docs/technical/implementation/M00-工程基础与本机环境执行文档.md)、[M00 验收记录](../readict-design/validation/implementation/m00/README.md)。这些文档不参与应用运行。

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
$readictSocketDirectory = Join-Path (Get-Location).Path '.local/tmp'
New-Item -ItemType Directory -Force -Path $readictSocketDirectory | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Duser.timezone=UTC -Dfile.encoding=UTF-8 `"-Djdk.net.unixdomain.tmpdir=$readictSocketDirectory`""
```

本机曾遇到 JDK 默认临时目录下的内部套接字连接失败，上述显式临时目录设置已通过验证。开发和测试使用不同终端，用完关闭终端。`backend/.env.example` 只说明变量，Spring Boot 不自动读取 `.env`。

当前机器还保留一个不提交的便利脚本，可在仓库根目录执行 `. ./.local/Load-LocalEnvironment.ps1 dev` 或 `. ./.local/Load-LocalEnvironment.ps1 test` 替代上述手动步骤。新克隆不包含该脚本和密码文件，不能将它们视为仓库自带工具。

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

打开 [本机状态页](http://127.0.0.1:5173/)。前后端只监听 127.0.0.1，端口为 5173／8080，占用即失败；Vite 保留 `/api` 路径代理到后端。各终端按 Ctrl+C 停止；若提示终止批处理，输入 Y。不停止共享 MySQL 或其他进程。

状态接口：`GET /api/v1/system/status`；健康检查：`GET /actuator/health/liveness`、`GET /actuator/health/readiness`。状态接口真实查询基线行；运行期依赖故障返回 503 Problem Details，启动前故障导致启动失败。匿名仅放行这三个 GET 接口，其他路径拒绝；M01 再实现认证与会话。

## 构建与测试

普通后端单测不需要数据库或凭据，在 backend/ 执行 `mvn test`。真实集成测试需在独立终端加载 test 配置，再在 backend/ 执行 `mvn -Pmysql-it verify`；只允许 readict_test，连接和迁移前校验配置，清理前复核实际目标，按精确 key 清理测试数据。

前端在 frontend/ 依次执行 `npm ci`、`npm run lint`、`npm test`、`npm run build`。详细结果和异常启动场景的历史验证见设计仓库的 M00 验收记录，本仓库不保留单次验收脚本。

迁移只由 Flyway 执行，禁 clean、自动 DDL 和 Mock 数据库回退；已执行 V1 不修改，后续新增 V2 等迁移。正式 jar 仅含 dev 配置，test 配置只在测试 classpath。生产部署不在当前范围。
