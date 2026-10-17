Xiaomi Album Syncer : 全量/增量/定时 下载小米云服务中的 相册/录音 到本地

# 项目结构

此文件夹为项目根目录。使用 monorepo 管理多个工程，前后端分离

# 项目概述

- **后端**: 于 `./server` 文件夹. Solon, Gradle, Kotlin, JVM 25.
  - 除非用户要求，每次更改此子工程后都应当执行`./gradlew clean compileKotlin`以确保编译通过，如果因为JVM版本不兼容导致编译失败，请用户自行解决
- **前端**: 于 `./web` 文件夹. Vue, TypeScript, Vite, Pinia, Tailwind CSS.
  - 除非用户要求，每次更改此子工程后都应当执行`yarn lint`, `yarn format`, `yarn build`以确保编译通过

# 注意事项

- 所有描述性文字与代码应该始终是面向 开发者/用户 的最终产物，不需要描述中间过程和演变原因。

# 公开仓库内容

- 仅提交通用部署说明与匿名化验证结论。具体环境的部署、运维记录、内网地址、域名、账号、私有路径和凭据不得进入 Git。
- Trellis 任务与会话记录仅保留在本地 `.trellis/tasks/`、`.trellis/workspace/`，这两个目录已忽略，不要强制添加。
