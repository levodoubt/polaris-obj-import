# Polaris Objuilder

> 简体中文 | [English](README_EN.md)

**外部 3D 模型导入模组（OBJ / glTF）** —— 把 Blender 制作的建筑、机械、地景导入 Minecraft 世界：整体渲染、PBR 材质、多 AABB 碰撞、右键交互与命令联动。

> 目标环境：Minecraft 1.21.1 · NeoForge 21.1.248 · Java 21 · 当前版本 **0.4.5**

---

## 功能特性

| 能力 | 说明 |
|---|---|
| **双格式导入** | `.obj`（含 `.mtl`）与 `.glb`（glTF 2.0 二进制，Blender 原生导出） |
| **整体渲染** | 单实体承载整个模型，几何量 = 原始面数；静态模型走 GPU 常驻 VBO 直绘，取消每帧 CPU 顶点提交 |
| **多材质** | `map_Kd` / `baseColorTexture` / 纯色，按材质分 draw call |
| **贴图** | 动态纹理逐像素贴图；`KHR_texture_transform`（UV 缩放/偏移/旋转）正确应用 |
| **自发光与照明** | `Ke` 视觉辉光 + `light:` 标记盒填充 `minecraft:light` 光源方块（可指定光照等级 1~15） |
| **透明度** | Alpha 半透明（BLEND，写深度）+ MASK 镂空 |
| **动画** | glb node TRS 刚体动画：循环播放 / 触发播放（once、loop），服务端广播同步；**每个摆放独立动画状态**（多扇同名门互不干扰） |
| **PBR** | Iris 光影下法线贴图 / 金属 / 粗糙度生效（labPBR 编码） |
| **权威摆放** | 坐标 + yaw + scale 摆放，SavedData 持久化，重进世界自动恢复 |
| **碰撞** | `col:` 标记盒 → 多 AABB 实体碰撞，支持可进入建筑 |
| **右键交互** | `col:x@动画名` 门类开关；`use:x` 纯交互盒（不挡人）；`$命令键` 右键执行真实命令（`give`、计分板、剧情…） |
| **点击手感** | 客户端宽容拾取 + 两层优先级（精确命中取最具体部件，近失手取最近盒），绕开原版 3 格实体交互距离限制 |
| **自检与自愈** | 进世界 / 登录 / 周期校核摆放实体：自动清理残留与重复、补齐缺失；交互失败给出具体原因 |
| **调试开关** | `/objdebug` 一键静默本模组全部调试输出（控制台日志 + 屏上提示 + 聊天回执）；`/objdebug feedback` 单独控制被本模组执行的命令的原版反馈 |
| **性能优化** | 静态模型 GPU 常驻 VBO 直绘；阴影 pass 细分 LOD + 独立缓冲 + 单面 CULL，超大模型帧率稳定 |

---

## 安装

1. 将 `polarisobjuilder-<版本>.jar`（如 `polarisobjuilder-0.4.5.jar`）放入游戏 `mods/` 目录
2. 需要 NeoForge 21.1.248 + Minecraft 1.21.1

**可选依赖**（缺省时功能自动降级，模组独立可用）：

- **Iris / Oculus**：开启 PBR（法线 / 金属 / 粗糙度）与光影支持；未安装时退回原版光照

---

## 模型放置

把 `.obj` / `.glb` 文件放入：

```
config/polarisobjbuilder/models/
```

引用路径相对该目录（如 `models/building.glb`，也可直接写文件名 `building.glb`）。

---

## 使用命令

### 摆放与管理（权威、持久化）

```
/objplace <x y z> <yaw> <scale> <ref>    # 摆放模型（支持 ~ 相对坐标）
/objlist                                  # 列出所有摆放（id / 引用 / 坐标 / yaw / scale）
/objremove <id>                           # 移除指定摆放（连带删碰撞体、光源方块、客户端缓存）
/objclear                                 # 清空全部摆放
```

示例：`/objplace ~ ~1 ~ 0 1.0 models/door.glb`（玩家脚下，yaw=0，1:1）

> 模型含 `col:` 碰撞盒时，yaw 必须是 90° 的倍数（0/90/180/270），否则碰撞盒与视觉无法轴对齐，命令会拒绝执行。

### 命令映射（`$命令键` → 真实命令）

标记盒名带 `$键` 时，右键会执行 `config/polarisobjbuilder/commands.json` 里对应的命令：

```json
{
  "commands": {
    "gun_take": {
      "cmds": [
        "execute if score %player% basement_gun matches 1 run give %player% tacz:modern_kinetic_gun[...] 1",
        "objremove %placement%"
      ],
      "consume": "result",
      "feedback": false
    },
    "lamp_toggle": { "cmd": "scoreboard players add %player% lamps 1", "consume": "never" }
  }
}
```

| 字段 | 说明 |
|---|---|
| `cmd` / `cmds` | 单条命令 / 多条命令数组（依次执行，**任一条未生效即中止后续**） |
| `perm` | 执行权限等级，默认 2 |
| `consume` | `result`（默认：命令未生效则回滚、可再点）· `always`（点了就消耗）· `never`（可重复点） |
| `feedback` | 是否保留该命令自身的原版回执（如 `give` 的"已将…给予…"），默认 false |

占位符：`%player%` `%uuid%` `%part%` `%placement%` `%x%` `%y%` `%z%`

```
/objcmd reload                            # 重新读取 commands.json（改完不必重启）
/objcmd list                              # 列出所有命令键
/objcmd test <键>                         # 以自己为对象试跑一次（不消耗、不改状态）
```

### 动画控制

```
/glbanim play <动画名> once|loop          # 触发播放（once 播一次 / loop 循环）
/glbanim stop <动画名>                    # 停止（保持当前姿势）
/glbanim reset <动画名>                   # 重置（回初始姿势，碰撞状态一并复位）
```

### 调试

```
/objdebug                                 # 查看「调试输出 / 命令反馈」两个开关的状态
/objdebug true|false|on|off               # 调试输出总开关（false = 隐藏全部调试输出）
/objdebug feedback true|false|on|off      # 被本模组执行的命令（give 等）是否保留原版反馈
```

- `false` 时静默三路：控制台诊断日志、屏上 actionbar 诊断提示、聊天框 `[Objuilder]` / `[Glb]` 前缀回执
- 不受影响：`error` 级报错（含异常栈）与无前缀的指令失败提示
- 两个开关都持久化在 `config/polarisobjbuilder-common.toml`，也可直接改配置文件

### 快捷导入（调试用，不写入存档）

```
/glbdomain [scale] <file>                 # glb 静态导入到玩家附近
/objdomain [scale] <file>                 # OBJ 纯视觉导入
```

---

## 模型制作约定（Blender）

标记盒 = 普通 box 物体，命名遵循 `<前缀>:<部件名>[@<动画名>][$<命令键>]`，与模型同一个 `.glb` 导出。

| 命名 | 语义 |
|---|---|
| `col:xxx` | 碰撞盒：参与实体碰撞（挡人 / 挡生物），不渲染 |
| `col:xxx@动画名` | 碰撞盒 + 右键开关：右键在开 / 关间切换并联动该动画；开状态解除碰撞 |
| `use:xxx` | 纯交互盒：可右键但不挡人（物件、道具、按钮类） |
| `use:xxx$命令键` | 交互盒 + 右键执行 `commands.json` 里的命令 |
| `light:xxx` | 光源盒：盒范围内填充 `minecraft:light`（缺省 15 级 = 满亮） |
| `light:xxx@等级` | 指定光照等级 1~15（压小照亮半径，缓解"光穿墙照亮隔壁"） |

- **薄墙厚度 ≥ 0.25 格**，避免玩家高速穿墙
- 想挡的地方放 `col:` 盒，想空的地方（门洞 / 室内）不放盒 → 天然支持可进入建筑
- `$` 必须写在 `@` 之后（`col:a@door$open`）；写成 `col:a$open@door` 会被判定为顺序错误并告警
- 光源盒只填充**空气**方块，范围上限 512 格，超出部分会被截断并告警

---

## 交互与点击手感

- 原版客户端拾取半径只有 3 格，且对薄盒极不友好；本模组改由客户端自己判定：
  **宽容膨胀**（`min(0.6, 0.25 + 体积×0.35)`）+ **两层优先级**（射线命中未膨胀盒者取体积最小 → 精确瞄准吃最具体部件；否则取最近的膨胀命中 → 近失手也能点中）
- 命中后经自定义包送服务端，由服务端按 **4.5 格 + 3 tick 防抖**自校验，最终复用同一套交互逻辑
- 未命中 / 被拒 / 命令未生效都会给出具体原因（`/objdebug false` 时静默）

---

## 持久化与自愈

- 摆放写入世界 `SavedData`（`polarisobjuilder_placements`），每个摆放 = 1 个视觉实体 + N 个碰撞体实体
- 进世界、玩家登录、每 5 秒做一次**校核**：清理孤儿（记录已删但实体还在）与重复副本、只在该位置区块已加载时补齐缺失、并把实体 UUID 回填记录
- `/objremove` 与 `/objclear` 幂等：记录已被删除时依然按摆放 id 兜底清理实体，避免"记录没了、模型还在、还能点"

---

## 开发者构建

```bash
.\gradlew.bat build --no-configuration-cache --offline "-Dorg.gradle.jvmargs=-Xmx2G -XX:MaxMetaspaceSize=1G"
```

产物：`build/libs/polarisobjuilder-<版本>.jar`

---

## 参考项目

本模组在实现过程中参考了以下开源项目：

| 项目 | 借鉴内容 |
|---|---|
| [MCglTF](https://github.com/ModularMods/MCglTF) | glTF 动画系统（node TRS 刚体 / 骨骼 / morph targets 网格动画）、PBR 材质的顶点色处理思路 |
| [MCObj](https://github.com/tom5454/MCObj) | OBJ / MTL 解析与导入流程 |

---

## 许可

[MIT License](LICENSE)
