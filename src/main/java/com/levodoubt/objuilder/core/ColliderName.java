package com.levodoubt.objuilder.core;

/**
 * 碰撞盒节点名解析（子工程 8）。
 *
 * <p>语法（定稿，见 `doc/2.1.13` §3）：
 * <pre>
 *   &lt;前缀&gt;:&lt;部件名&gt; [ @&lt;动画名&gt; ] [ $&lt;命令键&gt; ]
 * </pre>
 * <ul>
 *   <li>前缀 {@code col:} = 参与实体碰撞；{@code use:} = 不参与碰撞（玩家可穿过），其余规则完全相同</li>
 *   <li>其它前缀 → 返回 {@code null}（不是碰撞盒：不解析、照常渲染）</li>
 *   <li>{@code @动画名} → 该盒可点，点击走"动画 toggle"（子工程 7 语义）</li>
 *   <li>{@code $命令键} → 该盒可点，点击执行 {@code config/polarisobjuilder/commands.json} 里该键的真实命令</li>
 *   <li>两者都可缺省；两侧空白 trim；{@code @}/{@code $} 后为空视为未配置</li>
 *   <li>{@code $} 出现在 {@code @} 之前（如 {@code col:a$b@c}）→ 判为非法：{@code $} 段忽略、按纯碰撞盒处理
 *       （{@link #invalidOrder()} 置位，由调用方 warn，避免"点了没反应还查不出原因"）</li>
 * </ul>
 *
 * <p>本类**刻意不依赖任何 Minecraft/日志 API**（纯字符串处理）→ 可脱离游戏环境独立编译测试
 * （子工程 8 用 standalone java 跑过全部解析用例，见 `doc/2.1.13` §13）。
 *
 * @param partName     部件名（前缀之后、{@code @}/{@code $} 之前），会作为 {@code %part%} 占位符
 * @param animBinding  绑定的动画名，null = 无
 * @param cmdKey       命令映射键，null = 无
 * @param collides     true = {@code col:}（挡人）；false = {@code use:}（不挡人，仅交互）
 * @param invalidOrder 名字里 {@code $} 在 {@code @} 之前（非法顺序，{@code $} 段已被忽略）
 */
public record ColliderName(String partName, String animBinding, String cmdKey,
                           boolean collides, boolean invalidOrder) {
    /** 参与碰撞的前缀 */
    public static final String PREFIX_COLLIDE = "col:";
    /** 不参与碰撞（仅交互）的前缀 */
    public static final String PREFIX_USE = "use:";

    /** 是否可被准星选中/右键（配置了动画或命令键） */
    public boolean interactive() {
        return animBinding != null || cmdKey != null;
    }

    /** 日志用描述，如 {@code gun_wall@rifle_drop$gun_fall (col)} */
    public String describe() {
        StringBuilder sb = new StringBuilder(partName == null ? "" : partName);
        if (animBinding != null) sb.append('@').append(animBinding);
        if (cmdKey != null) sb.append('$').append(cmdKey);
        sb.append(collides ? " (col)" : " (use)");
        if (invalidOrder) sb.append(" [非法:$在@前]");
        return sb.toString();
    }

    /**
     * 解析节点名。
     *
     * @return 解析结果；{@code null} = 该 node 不是碰撞盒（不参与碰撞解析，且照常渲染）
     */
    public static ColliderName parse(String nodeName) {
        if (nodeName == null) return null;
        boolean collides;
        if (nodeName.startsWith(PREFIX_COLLIDE)) {
            collides = true;
        } else if (nodeName.startsWith(PREFIX_USE)) {
            collides = false;
        } else {
            return null;
        }

        String rest = nodeName.substring(4); // "col:" / "use:" 等长
        int at = rest.indexOf('@');
        int dollar = rest.indexOf('$');
        boolean invalidOrder = at >= 0 && dollar >= 0 && dollar < at;

        // 部件名 = 到第一个 @ 或 $ 为止（非法顺序时仍以 $ 为界：$ 到 @ 之间的整段视为垃圾丢弃）
        int cut = rest.length();
        if (at >= 0) cut = Math.min(cut, at);
        if (dollar >= 0) cut = Math.min(cut, dollar);
        String partName = rest.substring(0, cut).trim();

        String anim = null;
        if (at >= 0) {
            int end = (!invalidOrder && dollar > at) ? dollar : rest.length();
            anim = rest.substring(at + 1, end).trim();
            if (anim.isEmpty()) anim = null;
        }
        String cmd = null;
        if (!invalidOrder && dollar >= 0) {
            int end = (at > dollar) ? at : rest.length();
            cmd = rest.substring(dollar + 1, end).trim();
            if (cmd.isEmpty()) cmd = null;
        }
        return new ColliderName(partName, anim, cmd, collides, invalidOrder);
    }
}
