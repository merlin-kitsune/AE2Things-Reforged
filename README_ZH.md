# AE2Things Reforged

[English](README.md) | **简体中文**

这是 [AE2 Things](https://github.com/Technici4n/AE2Things-Forge) 的优化分支：完整保留原模组的全部功能，在每条 Minecraft
版本线上都对齐当时最新的 AE2 API，并大幅削减了大网络下原本由本模组带来的额外性能开销。

*DISK = Deep Item Storage disK（深度物品存储磁盘）。* 一块**没有类型上限**的存储元件：一个物品占一字节。

## 下载

| Minecraft | 加载器 | 模组版本 | 前置要求 | 文件 |
|---|---|---|---|---|
| 1.21.1 | NeoForge 21.1.248+ | **1.4.5** | AE2 19.2.18+（低于 20） | `AE2-Things-1.4.5.jar` |
| 1.20.1 | Forge 47.1.47+ | **1.3.1** | AE2 15.4.12+（低于 16） | `AE2-Things-1.3.1.jar` |

两条线都保持 `modId=ae2things`、相同的方块/物品 id，以及与各自 Minecraft 版本上游发布版完全一致的存档格式，因此可以直接
替换——已有存档照常使用。

1.21.1 需要 Java 21，1.20.1 需要 Java 17。

## 本分支改了什么

### 性能

DISK 元件把内容存在侧表（`world/data/disk_manager.dat`）而不是物品里，这正是「没有类型上限」的实现基础。上游在**每一次**
内容变更时都把整张侧表重写一遍，在大网络里这就成了本模组最大的开销来源。

- 每个 key 只序列化一次并复用结果，不再在每次落盘时对所有已存 key 重跑一遍 DFU codec。
- 只有数量发生变化的磁盘，落盘时原地更新数量数组，不重建、不重编码整个 key 列表。
- 物品总数按已知增量维护，不再每次变更都从全部已存 key 重新累加。
- 读路径不再创建（并标脏）侧表条目：读取现在是只读的，只是被显示或扫描的磁盘不再触碰存档文件。
- 加载侧表不再将其标脏，因此世界加载后的第一次自动保存不会重写整张表。
- 渲染不再每帧构造一次完整的 cell inventory：物品染色与 tooltip 直接从物品堆的 NBT 读取状态。

### 缺陷修复

- 克隆磁盘会复制内容，复制品不再与原盘共用（并覆盖）同一份数据。`/ae2things recover` 同样改为铸造新 id 并复制条目，
  而不是给出第二个指向同一 id 的物品。
- 数量数组与 key 数组长度不一致的磁盘不再崩溃，也不会静默丢数据。
- 无法读取的侧表条目（缺失/格式错误的 id、无法解码的数据）会被原样保留并在下次保存时写回，而不是被抹掉；单个坏条目也
  不会连带清空整张表。
- 仅用于探测容量的元件（`SIMULATE`）不再铸造磁盘 id，也不写入物品 NBT。
- 拆解磁盘会移除它的侧表条目，空条目不再不断堆积。
- 服务端侧表不可用时，写入会被拒绝，而不是把内容静默地只留在内存里。
- 补齐了缺失的翻译键，原先写死的 tooltip 现在可以翻译。

### 兼容性

命令帮助文本与 tooltip 的调用路径与 [AE2 MEGA Things](https://github.com/Lapis256/AE2MEGAThings) 保持逐字节兼容——
该模组通过 mixin 直接改写本模组的这些位置。已分别用 AE2 MEGA Things **1.0.8**（1.20.1，装上该模组的完整实机联调）与
**2.0.4**（1.21.1，符号与注入点契约核对）验证。

## 使用

- 合成 DISK 外壳并与元件组件结合，或直接合成驱动器。
- 把磁盘放进 **ME 驱动器**（单独读取时可放进 **ME 箱子**）。
- 在元件工作台里分区，照常加装模糊卡或反向卡。
- `/ae2things getuuid` 打印手中磁盘的 id。
- `/ae2things recover <uuid> [DISK]` 按该 id 生成一块磁盘（复制内容，仅限管理员）。

## 构建

本仓库包含两套互相独立的构建：根工程是 1.21.1 NeoForge 线，`forge-1.20.1/` 是 1.20.1 Forge 线，带自己的 wrapper 与配置。

```bash
# 1.21.1 / NeoForge
./gradlew build                    # -> build/libs/AE2-Things-<mod_version>.jar

# 1.20.1 / Forge
cd forge-1.20.1 && ./gradlew build  # -> build/libs/AE2-Things-<mod_version>.jar
```

每条线的版本号来自它自己的 `gradle.properties`（`mod_version`），可用 `-Pmod_version=` 覆盖。

## 致谢

- 原模组作者：**ProjectET**（Fabric 版）与 **Technici4n**（Forge / NeoForge 移植）。
- 本分支由 **MerlinKitsune** 维护，与原作者的立场无关，也未获其背书。
- 采用 MIT 许可证，见 [LICENSE](LICENSE)。
