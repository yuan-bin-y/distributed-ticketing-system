# Nacos配置样例

此目录的YAML是需要在Nacos配置管理页面发布的内容，应用不会直接读取磁盘上这个目录。

统一选择Namespace `public`、Group `TICKET_GROUP`，以各文件名作为Data ID，格式YAML。共7份：公共 `ticket-common.yaml` 和六个服务的专属文件。仅启动某个服务时，至少发布公共文件与该服务文件。

已有同名配置时合并相应字段，保留其他配置。服务IDEA Active profiles填写 `nacos` 后重启。未启用profile时继续使用原本地配置。

公共文件先加载，专属文件后加载；专属文件可覆盖公共同名键。`logging.level.*` 可以动态刷新，Event的 `ttl-ms` 可以动态刷新；其他样例参数在文件注释中说明需要重启。数据库密码与私钥不放入这些样例。

完整容器启动和环境变量配置见 [Docker 部署说明](../../docs/docker-deployment.md)。
