# Python 游戏运行时

本模块是用户于 2026-10-11 授权的增量重构，继承根目录的产品、事实、数据保护和验证要求。
根目录 Java、LangChain4j、Javadoc、Maven 要求适用于原 Java 模块；这里使用 Python、LangChain、中文 docstring、uv/pytest。
代码结构为 API → Application Service → Domain → Repository / Model Gateway。模型不能直接写库。
SQL 迁移在 migrations，使用独立 SQLite，不连接旧游戏或资料库数据库，不迁移真实玩家。
真实模型调用前明确模型、预算和回放范围；工程 fixture 只能验收请求、存储及界面，不能验收玩法。
本地启动使用单进程和 loopback，测试会话不是生产账户系统。
