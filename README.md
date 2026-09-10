# Polaris Objuilder

> 简体中文 | [English](README_EN.md)

**外部 3D 模型导入模组（OBJ / glTF）** — 把 Blender 制作的建筑、机械、地景导入 Minecraft 世界，支持动画、PBR 材质、碰撞与右键交互。

> 目标环境：Minecraft 1.21.1 · NeoForge 21.1.248 · Java 21

---

## 功能特性

| 能力 | 说明 |
|---|---|
| **双格式导入** | 支持 `.obj`（含 `.mtl`）与 `.glb`（glTF 2.0 二进制，Blender 原生导出） |
| **纯视觉整体渲染** | 单个实体承载整个模型，几何量 = 原始面数（亿格级大模型不卡死） |
| **多材质** | `map_Kd` / `baseColorTexture` / 纯色，按材质分 draw call |
| **贴图** | 动态纹理逐像素贴图；`KHR_texture_transform`（UV 缩放/偏移/旋转）正确应用 |
| **自发光** | Ke 视觉辉光 + `minecraft:light` 光源方块真实照亮 |
| **透明度** | Alpha 半透明（BLEND）+ MASK 镂空 |
| **动画** | glb node TRS 刚体动画：循环播放 + 触发播放（once/loop）+ 服务端同步 |
| **PBR** | Iris 光影下法线贴图 / 金属 / 粗糙度生效（labPBR） |
| **权威摆放** | 坐标 + yaw + scale 摆放，SavedData 持久化，重进恢复 |
| **碰撞** | `col:` 前缀碰撞盒 → 多 AABB 实体碰撞，支持可进入建筑 |
| **交互** | 右键开关门（`col:xxx@AnimName`），部件级独立触发 |
| **剧情联动** | 可选集成 StoryCore：剧情脚本 action 节点驱动模型动画 |
| **性能优化** | 静态模型 GPU 常驻 VBO 直绘（消除每帧 CPU 顶点提交）；阴影 pass 细分 LOD + 独立缓冲 + 单面 CULL，超大模型帧率稳定 |

---

## 安装

1. 将 `polarisobjuilder-1.0.0.jar` 放入游戏 `mods/` 目录
2. 需要 NeoForge 21.1.248 + Minecraft 1.21.1

**可选依赖**（缺省时功能自动降级，模组独立可用）：
- **Iris / Oculus**：开启 PBR（法线/金属/粗糙）与光影支持
- **polarisstorycore**：开启剧情 action 联动（`polarisobjuilder:anim`）

---

## 模型放置

将 `.obj` / `.glb` 文件放入：

```
config/polarisobjuilder/models/
```

引用路径即相对该目录（如 `models/building.glb`，或直接用文件名 `building.glb`）。

---

## 使用命令

### 摆放与管理（权威）

```
/objplace <x y z> <yaw> <scale> <ref>    # 摆放模型到指定坐标（支持 ~ 相对坐标）
/objlist                                  # 列出所有摆放
/objremove <id>                           # 移除指定摆放
/objclear                                 # 清空全部摆放
```

示例：`/objplace ~ ~1 ~ 0 1.0 models/door.glb`（玩家脚下，yaw=0，1:1）

### 动画控制

```
/glbanim play <name> once|loop            # 触发动画播放
/glbanim stop <name>                      # 停止（保持当前姿势）
/glbanim reset <name>                     # 重置（回初始姿势）
```

### 快捷导入（调试用，非持久化）

```
/glbdomain [scale] <file>                 # glb 静态导入（摆玩家附近）
/objdomain [scale] <file>                 # OBJ 纯视觉导入
```

---

## 模型制作约定（Blender）

| 命名 | 语义 |
|---|---|
| `col:xxx` | 碰撞盒（box 物体，不渲染，仅阻挡） |
| `col:xxx@AnimName` | 动态碰撞盒 + 右键交互（门）：右键在开/关间切换，联动 `AnimName` 动画 |

- 碰撞盒 = 普通 box 物体，命名加 `col:` 前缀，与模型同一 `.glb` 文件导出
- **薄墙厚度 ≥ 0.25 格**，避免玩家高速穿墙
- 想挡的地方放盒，想空的地方（门洞/内部）不放盒 → 天然支持可进入建筑
- 带 `col:` 盒的模型摆放 yaw 限 0/90/180/270（碰撞盒须轴对齐）

---

## 剧情联动（可选）

安装 StoryCore 后，剧情脚本可用 action 节点驱动模型动画：

```json
{
  "id": "open_door",
  "type": "action",
  "action": "polarisobjuilder:anim",
  "actionParams": { "name": "OpenDoor", "mode": "once" }
}
```

---

## 开发者构建

```bash
.\gradlew.bat build --no-configuration-cache --offline "-Dorg.gradle.jvmargs=-Xmx2G -XX:MaxMetaspaceSize=1G"
```

产物：`build/libs/polarisobjuilder-0.2.0.jar`

---

## 参考项目

本模组在实现过程中参考了以下开源项目：

| 项目 | 借鉴内容 |
|---|---|
| [MCglTF](https://github.com/ModularMods/MCglTF) | glTF 动画系统（node TRS 刚体 / 骨骼 / morph targets 网格动画）、PBR 材质的顶点色处理思路 |
| [MCObj](https://github.com/tom5454/MCObj) | OBJ / MTL 解析与导入流程 |

**可选依赖**（见“安装”）：
- **Iris / Oculus**：开启 PBR（法线/金属/粗糙）与光影支持
- **polarisstorycore**：开启剧情 action 联动（`polarisobjuilder:anim`）

---

## 许可

[MIT License](LICENSE)
