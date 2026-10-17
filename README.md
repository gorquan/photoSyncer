# photoSyncer

将小米云相册、录音与 Apple iCloud Photos 同步到本地，支持定时增量下载和 Synology Photos 展示。

本项目基于 [XiaomiAlbumSyncer](https://github.com/Coooolfan/XiaomiAlbumSyncer) 开发。**本仓库新增的 iCloud、群晖 Photos 与删除同步功能，需要使用本仓库构建产物；上游镜像不能代替。**

## 功能

- 小米与 iCloud 多账号绑定、相册选择、定时及手动同步。
- 全量、时间线增量、位点增量模式；缺失或损坏的托管文件可在后续同步修复。
- 群晖 Photos 模式按厂商、账号、图库隔离目录，同一资源出现在多个所选相册时只导出一份。
- Apple Live Photo 保留图片和配套视频的同名配对及元数据；小米动态照片保留原始单文件结构。
- 原件库与展示文件分开保存，支持来源校验、本地 SHA-256 校验、照片和视频时间信息补齐。
- 可选择同步云端删除，或保留本地文件作备份。
- Web 管理界面、[路径表达式](docs/expression-target-path.md)、[HTTPS](docs/ssl-suppot.md) 和 [Passkey](docs/passkey-login.md)。

群晖兼容范围、实机结果与限制见 [Synology Photos 使用说明](docs/synology-photos.md)。HEIC／视频预览取决于 Photos、Synology Image Assistant 和客户端解码能力；云端虚拟相册、收藏及人脸关系不自动迁移。

## 从源码部署

需要 Git、Node.js 24.20+（24 系列）、JDK 25、Docker。先克隆本仓库，以下命令在仓库根目录执行：

```bash
(cd web && npm install && npm run build)
mkdir -p server/src/main/resources/static
cp -R web/dist/. server/src/main/resources/static/
(cd server && ./gradlew clean solonJar -PappVersion="$(git rev-parse --short HEAD)")
```

将 `server/build/libs/` 中生成的可执行 JAR 复制为 `docker/app.jar`。不要选择带 `-plain` 后缀的 JAR。

```bash
cp "server/build/libs/XiaomiAlbumSyncer-$(git rev-parse --short HEAD).jar" docker/app.jar
mkdir -p db download
docker compose -f docker/docker-compose.yml up -d --build
```

构建和复制期间请保持 Git 提交不变。访问 `http://服务器IP:8232`，首次运行设置应用管理密码。该密码与小米、Apple 和 DSM 密码分别管理。远程访问建议通过反向代理启用 HTTPS。

Compose 使用本地构建的 `photosyncer:local` 镜像，包含 JRE 25 和 ExifTool。默认持久化目录为仓库下的 `db` 和 `download`。也可设置以下环境变量后启动：

| 变量 | 用途 | 默认值 |
| --- | --- | --- |
| `PHOTO_SYNCER_STATE_DIR` | 宿主机数据库与原件目录 | `../db`（相对 compose 文件） |
| `PHOTO_SYNCER_EXPORT_DIR` | 宿主机展示文件目录 | `../download`（相对 compose 文件） |
| `PHOTO_SYNCER_PORT` | 对外服务端口 | `8232` |

容器内数据库为 `/app/db/xiaomialbumsyncer.db`，原件为 `/app/db/media`，展示根目录为 `/photos`。数据库文件名保留历史名称，不影响功能。确保挂载目录可写。原件库和展示根目录必须互不包含，并保持足够空间容纳两份媒体及处理中的临时文件。

### 群晖 Container Manager

先按上文构建镜像，或将构建机器的镜像通过 `docker save`／`docker load` 导入群晖，再使用 compose 文件启动（已导入镜像时无需 `--build`）。也可以在群晖上传 `docker/app.jar` 与 `docker/Dockerfile.jvm` 后构建镜像。

以个人空间为例，将 `PHOTO_SYNCER_EXPORT_DIR` 设置为用户 Photos 下的专用目录，例如 `/volume3/homes/用户名/Photos/photoSyncer`；共享空间则使用实际 `/volumeN/photo` 下的专用目录。卷号按设备实际情况填写。

若群晖默认 Docker 网络无法出网，可在镜像构建时添加 `--network host`，并在部署用的 compose 中设置 `network_mode: host`、环境变量 `SERVER_PORT: "8232"`，同时移除 `ports` 映射。任务目标路径仍为 `/photos`。使用宿主机用户 UID/GID 运行时，还需给该用户可写的 `/app/logs` 挂载。

`PHOTO_SYNCER_STATE_DIR` 例如 `/volume3/docker/photoSyncer/db`，必须位于 Photos 索引目录之外。任务目标填写容器内路径 `/photos`。不要直接填写宿主机卷路径。无需特权模式，也无需修改群晖 Photos 数据库或 `@eaDir`。

### 直接运行 JVM

安装 JDK／JRE 25 和 ExifTool，按上文构建 JAR，在独立工作目录运行：

```bash
APP_DB_PATH=./db/xiaomialbumsyncer.db java --enable-native-access=ALL-UNNAMED -jar /实际路径/app.jar
```

默认监听 `8080`。在应用设置中填写本机 ExifTool 的可执行路径。`PHOTO_SYNCER_MEDIA_ROOT` 可指定原件根目录，默认在数据库目录下的 `media`；它必须持久化并与展示目录分开。

## 绑定账号和创建任务

1. 打开设置页，绑定小米或 Apple 账号。
   - 小米推荐扫码添加；也支持填写 `userId` 和 `passToken`。首次访问云相册可能还需要完成小米云的设备／短信授权。
   - Apple 使用 Apple Account 登录，按提示提交双重认证验证码；账号会话失效后需要重新登录。中国大陆账号选择对应服务区域。
2. 同步账号列表会显示两家厂商的认证状态和最近检测时间，可点击“检测认证”检查当前会话。旧账号在首次检测前显示“待检测”；网络或服务异常显示“检测失败”，不等同于登录失效。
3. 在任务页更新所选账号的远程相册列表。
4. 创建任务，选择账号、相册、同步模式、时间及媒体类型。Cron 示例 `0 0 23 * * ?` 表示每天 23 点，按服务运行时区执行。
5. 在“保存与同步”中选择“备份模式”或“镜像模式”，并独立设置“适配群晖 Photos”。使用群晖时打开适配开关，目标填 `/photos`。图片和实况配套视频分别控制；需要保留 Apple 动态部分时开启配套视频。
6. 可先手动执行并查看任务历史；确认结果后启用定时执行。

群晖模式的目录示例：

```text
/photos/icloud-1/<图库摘要>/asset_<照片摘要>.heic
/photos/icloud-1/<图库摘要>/asset_<照片摘要>.mov
/photos/xiaomi-2/<图库摘要>/asset_<照片摘要>.jpg
```

旧任务保留原路径模式。切换群晖模式建议使用新空目录，再核对旧导出，避免 Photos 索引出重复项目。

## 删除与备份

任务中的“本地保存模式”控制云端删除后的本地行为，与“适配群晖 Photos”开关独立：

| 设置 | 云端确认删除后的行为 |
| --- | --- |
| 备份模式 | 保留已下载的本地文件和原件，适合作长期备份 |
| 镜像模式 | 清理对应托管展示文件及没有其他任务引用的历史原件 |

**新建任务默认镜像模式；升级前未设置删除同步的旧任务保持备份模式。** 手机删除需先同步到云端，本地下一次成功检查后才清理。Apple 实况图片及配套视频作为一组处理。

应用只清理具有归属凭据、路径安全且校验匹配的托管文件。相册移出、登录失效、分页失败不能证明删除；外部修改、符号链接或其他任务仍引用的文件会保留并报告。小米对象在首次观察前已永久清空回收站时，可能无法取得充分证据，会保留本地文件。应用不会删除云端照片。

当前通过任务中的保存模式选择备份或跟随删除，不自动判断用户是在整理照片还是释放云端空间。详细规则见 [删除同步说明](docs/synology-photos.md#手机删除后同步清理)。

## 升级与备份

升级前停止容器，一起备份 `db`（含原件库）、展示目录及部署配置，再替换镜像。仅备份 SQLite 无法恢复原件与发布凭据。不要在运行时只复制 SQLite 主文件而遗漏 WAL；可停止服务后复制，或使用 SQLite backup API。

旧版 v2 数据导入入口在设置页。迁移前备份旧数据库，将其作为 `/app/old.db` 挂载给已完成密码初始化、尚无相册和下载记录的新实例，再执行“从 V2 导入数据”。迁移后检查任务目标路径和账号状态。

## 开发与验证

```bash
(cd web && npm run lint && npm run format && npm run build)
(cd server && ./gradlew clean compileKotlin test apiE2eJvm)
(cd xas-mock && go test ./...)
```

后端使用 Kotlin／Solon／Jimmer／SQLite，前端使用 Vue／TypeScript／Vite。实机格式验证记录见 [群晖验证](docs/plan/synology-photos-validation.md)，删除边界和自动化断言见 [删除验证](docs/plan/cloud-deletion-validation.md)。

本项目保留上游 [许可证](LICENSE) 与归属信息。
