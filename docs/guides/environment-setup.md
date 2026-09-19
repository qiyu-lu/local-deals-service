# 本地环境与常见问题

## 依赖容器

开发环境用主 Compose 启动（固定端口 3306/6379/9876/10911/9200，数据 bind mount 到 `./*-data/`）：

```bash
docker compose up -d mysql redis namesrv broker elasticsearch
docker compose --profile dev up -d nginx canal-server   # 需要前端或 Canal 时
```

业务库为 `local_deals`，由 Flyway 在应用启动时迁移。

集成测试与压测使用隔离栈（独立 compose project、`127.0.0.1` 上的独立端口、named volume），
不会碰开发环境的数据：

```bash
scripts/stack.sh up                 # MySQL/Redis/RocketMQ/ES
scripts/stack.sh it '*IT'           # 在隔离栈上用 Java 8 跑集成测试
scripts/stack.sh build && scripts/stack.sh app-start
scripts/bench.sh users 100000       # 压测用户与 token
scripts/bench.sh step 500 1000 2000 # 开环阶梯压测，结果写 benchmark/v2/<milestone>/summary.csv
scripts/stack.sh down               # 删除隔离栈及其 volume
```

## 后端接口检查

可以访问下面的接口检查后端是否正常启动：

```text
http://localhost:8083/shop-type/list
```

如果通过前端和 Nginx 访问，打开浏览器开发者模式，切换到手机模式后访问：

```text
http://localhost:8088/
```

![手机模式图标](../../figure/手机模式.png)

## JDK 版本问题

如果运行或编译时报错：

```text
java: java.lang.NoSuchFieldError:
Class com.sun.tools.javac.tree.JCTree$JCImport does not have member field 'com.sun.tools.javac.tree.JCTree qualid'
```

通常是 JDK 版本和项目依赖不匹配。该项目建议使用 JDK 8。

### IDEA Project SDK

路径：

```text
File -> Project Structure -> Project
```

设置：

```text
Project SDK: JDK 1.8
Project language level: 8
```

### IDEA Module SDK

路径：

```text
File -> Project Structure -> Modules -> local-deals-service -> Dependencies
```

设置：

```text
Module SDK: JDK 1.8
```

### Maven Runner

路径：

```text
Settings -> Build Tools -> Maven -> Runner
```

设置：

```text
JRE: Project JDK
```
