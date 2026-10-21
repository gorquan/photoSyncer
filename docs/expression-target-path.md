# 保存路径兼容说明

当前版本的 `targetPath` 只表示统一导出根目录，例如 `/photos`。所有任务都在这个根目录下按厂商、账号和图库隔离保存，群晖 Photos 开关不会改变根目录或账号归属。

旧版本曾支持 `${album}`、`${fileName}` 等路径表达式。为了避免旧任务升级后把文件写到账号目录之外，读取旧配置时只取第一个 `${...}` 插值之前的字面目录作为根目录；插值内容不再参与新文件路径生成。保存任务后，前端也只提交根目录。

新的目录规则是：

```text
/photos/<provider>-<account>/<library-or-zone>/<album>/<cloud-file-name>  # 普通云端原件布局
/photos/<provider>-<account>/<library-or-zone>/<stable-resource-name>     # 群晖 Photos 布局
```

普通布局保留云端原件内容；群晖 Photos 布局使用稳定资源名并处理配套元数据。两种布局共享同一个根目录和账号隔离边界。
