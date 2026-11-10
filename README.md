# RabbitMine

为生存服务器提供可重复开采的矿场。

**Minecraft 1.21.1 · NeoForge 21.1.265+（21.1.x） · Java 21**

[发布页](https://github.com/RabbitWorldTeam/RabbitMine/releases) · [Wiki 指南](https://github.com/RabbitWorldTeam/RabbitMine/wiki) · [问题反馈](https://github.com/RabbitWorldTeam/RabbitMine/issues)

## 主要功能

- 用木锄选区，设置矿场边界和刷新安全点。
- 按自定义权重分批刷新矿石和填充方块。

## 安装

将 `rabbitmine-1.21.1-neoforge-2.0.0.jar` 放入客户端和服务端的 `mods/`。

## 常用入口

- `/rabbitmine help` · 查看矿场管理指令，需要权限等级 2。
- `config/rabbitmine.json` · 矿场区域、方块权重和安全点。
- `config/rabbitmine-common.toml` · 选区工具、刷新速度和 HTTP 端口。

采矿锤由 [RabbitTools](https://github.com/RabbitWorldTeam/RabbitTools) 提供。

## 从源码构建

`./gradlew build`，Windows 使用 `.\gradlew.bat build`。JAR 输出到 `build/libs/`。

[MIT](LICENSE)
