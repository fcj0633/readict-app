# Readict 应用工程

M00 工程基础已实现：Java 后端、React 前端、本机 MySQL 隔离、Flyway、MyBatis-Plus／XML、统一错误和状态检查。注册、登录、阅读、词典、翻译、收藏及音频尚未开发。

设计基线：[M00 执行文档](../readict-design/docs/technical/implementation/M00-工程基础与本机环境执行文档.md)、[系统设计](../readict-design/docs/technical/architecture/Readict-V1.1-系统技术设计.md)、[ADR-0002](../readict-design/docs/technical/decisions/ADR-0002-业务持久层采用MyBatis-Plus与XML.md)。设计仓库为平级参考，不是运行依赖。

## 环境与目录

Windows 11、PowerShell 7、JDK 21、Maven 3.9、Node 24／npm 11、本机 MySQL 8.0。准备和启动负向检查脚本还使用 Python 3.10+ 标准库，无须安装 pip 包。Docker 不参与。

- `backend/`：Spring Boot 4.1.2、MP Boot 4 Starter 3.5.18、Flyway、Spring Security。
- `frontend/`：React 19.3.1、Vite 8.3.8、TypeScript 7.0.2；独立锁文件。
- `scripts/`：本机数据库准备、环境加载、启动失败检查。
- `docs/m00-verification.md`：真实结果和 T01～T13 验收记录。
- `.local/`：仅本机秘密和 JDK 临时目录，禁止提交。
- `.artifacts/`、`backend/target/`、`frontend/dist/`：忽略的日志与产物。

## 首次准备数据库

在仓库根目录打开终端，确认 MySQL80 运行且 `mysql` 在 PATH：

```powershell
python scripts/prepare_database.py
```

按隐藏提示输入本机 MySQL root 密码。程序先检查目标库和账号；首次准备创建 `readict_dev`、`readict_test` 和各自的 app／migrate 账号，生成四个不同的随机密码。数据库字符集为 utf8mb4，排序规则为 utf8mb4_0900_bin；不会修改服务器全局排序规则。

密码保存于受忽略的 `.local/database.json`，目录 ACL 仅授权执行准备的 Windows 用户；root 密码不保存。正常重复运行只验证，不更改现有密码和权限。若发现同名库／账号但缺少本地凭据，或发生部分准备失败，应先检查现状，不能删除重建或直接重置密码。

运行账号仅有对应库的 SELECT／INSERT／UPDATE／DELETE；迁移账号增加对应库的 CREATE／ALTER／DROP／INDEX／REFERENCES。四个账号不能访问另一个业务库。DROP 权限不意味着允许删库、清库或任意测试清理。

## 启动本机开发环境

终端 A，从仓库根目录执行：

```powershell
. ./scripts/Load-LocalEnvironment.ps1 dev
Set-Location backend
mvn spring-boot:run '-Dspring-boot.run.profiles=dev'
```

首次启动由迁移账号执行 V1，建立 `system_metadata` 和唯一 application 基线行；Flyway 自动维护自己的历史表。重复启动只校验迁移，不重复插入。

终端 B，从仓库根目录执行：

```powershell
Set-Location frontend
npm ci
npm run dev
```

浏览器访问 [本机状态页](http://127.0.0.1:5173/)。前端访问相对路径 `/api/v1/system/status`，Vite 保留 `/api` 原路径代理到后端。前后端均绑定 `127.0.0.1`，端口分别为 5173／8080，占用即报错，不自动漂移。停止时在各自终端按 Ctrl+C；Maven／npm 如提示终止批处理，输入 Y。应用进行优雅关闭，不结束其他 Java 或 MySQL 进程。

环境加载脚本将变量设置到当前终端及其子进程；开发与测试使用不同终端。`backend/.env.example` 只是说明，Spring Boot 不自动读取 `.env`。使用完毕关闭终端，或清除 `DB_APP_USER`、`DB_APP_PASSWORD`、`DB_MIGRATION_USER`、`DB_MIGRATION_PASSWORD` 和 `JAVA_TOOL_OPTIONS`。

脚本同时设置 JVM UTC 和项目 `.local/tmp` 套接字目录。本机验证发现，JDK 默认 TEMP 下的内部 AF_UNIX 连接曾失败；显式指定 `jdk.net.unixdomain.tmpdir` 后已通过 Tomcat 和 HTTP 测试。不要删除这一步直接启动后再以关闭安全措施解决网络错误。此配置只影响当前终端，不修改系统 JDK 或全局环境。

## 检查接口

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/v1/system/status
Invoke-RestMethod http://127.0.0.1:5173/api/v1/system/status
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/liveness
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/readiness
```

状态接口真实读取 MySQL 的 application 基线行，成功返回：

```json
{"status":"UP","service":"readict","requestId":"00112233-4455-6677-8899-aabbccddeeff"}
```

请求 ID 每次生成，也接受合法 UUID 的 `X-Request-Id`；响应头与正文一致。运行期依赖不可用或基线异常返回 503 `application/problem+json`，错误码 `SYSTEM_NOT_READY`。数据库在启动前不可用时，应用直接启动失败，不会提供一个可访问的 503 接口。

liveness 只检查应用生命周期，readiness 检查生命周期、数据库和元信息。匿名允许清单仅含以上三个 GET 路由；根 health、其他管理端点和其他路径返回 401／403 Problem Details。保留 CSRF，不提供默认账号、表单登录或 HTTP Basic。M01 再实现真正的认证和会话能力。

## 构建与测试

后端普通单测不需要数据库或秘密，位于 `backend/`：

```powershell
mvn test
mvn help:effective-pom '-Doutput=target/effective-pom.xml'
mvn dependency:tree '-Dincludes=com.baomidou:*,org.mybatis:*,org.mybatis.spring.boot:*,org.flywaydb:*,com.mysql:*,com.zaxxer:*'
```

另开测试终端，从仓库根目录加载测试账号再执行：

```powershell
. ./scripts/Load-LocalEnvironment.ps1 test
Set-Location backend
mvn -Pmysql-it verify
```

`*IT` 使用本机真实 `readict_test`，连接／迁移前校验 profile、URL、库名及账号；清理前再通过只读 Mapper 和连接元信息确认实际目标。只精确删除本次生成的 `it_<UUID>` key，不操作 application 基线，不执行全表删除、DROP DATABASE 或 Flyway clean。Failsafe 配置禁止零 IT 假通过。测试专用 Mapper、失败服务和实体不进入正式 jar。

前端，位于 `frontend/`：

```powershell
npm ci
npm run lint
npm test
npm run build
```

停止 8080／5173 的本工程进程后，可在仓库根目录运行额外启动失败检查：

```powershell
python scripts/verify_startup.py
```

该脚本检查缺少凭据、缺少 profile、无效迁移密码及端口占用的失败退出；端口冲突用临时监听模拟，不结束任何既有进程。使用专用开发账号，可能在启动检查中首次执行开发库 V1；无效凭据不是对实际账号改密。诊断日志仅写忽略目录。

## 配置和后续开发边界

只支持显式 dev／test profile；测试 profile 的资源只在测试 classpath，正式 jar 不能作为 test 环境启动。当前数据库固定 127.0.0.1:3306；不支持通过任意 DB_HOST／DB_PORT 或 URL 将库切换到其他位置。迁移 URL 必须与业务 URL 一致。无默认凭据，无 H2、自动 DDL、schema.sql、data.sql 或 Mock 回退；prod profile 当前会拒绝启动，生产部署另行设计。

业务持久层复用单一 DataSource、Spring 事务、BaseMapper 与绑定参数 XML。UUID 使用应用生成的 input ID 和固定 big-endian BINARY(16) 处理器；时间使用 UTC Calendar 与 Instant，DATETIME(3) 保留毫秒。后续 UUID 业务表仍需补充真实 BaseMapper 写读验收，M00 的 UUID 证据只包含自动映射元数据与真实只读 SQL 往返。

V1 执行后不可修改，修正和后续模块从 V2 新增。Session JDBC、用户和业务表在对应模块引入；不将本机账号方案或临时安全允许清单视为生产部署方案。
