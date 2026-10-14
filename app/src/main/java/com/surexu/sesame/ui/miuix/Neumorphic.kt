package com.surexu.sesame.ui.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Sesame-Wu 拟态（Neumorphism / Soft UI）设计系统。
 *
 * 核心思想：界面元素与背景使用**同一基色**，
 * 完全依靠柔和的双向光照（左上高光 + 右下阴影）塑造体积感——
 * 凸起 [neuRaised] 像从背景里"长出来"，凹陷 [neuPressed] 像被"按进去"。
 *
 * 配色不再写死，全部从 [MiuixTheme] 的语义色派生，因此：
 * - 跟随 [top.yukonga.miuix.kmp.theme.ThemeController] 的配色方案（浅色/深色自动适配）
 * - 基色与页面底色严格同色，满足拟态"元素即背景"的前提
 *
 * 色值映射（以 miuix 标准浅色方案为例）：
 * | 角色 | 语义色 | 浅色实际值 |
 * | --- | --- | --- |
 * | 基色 base | `surface`（页面底色） | `#F7F7F7` |
 * | 左上高光 | `surfaceContainer` | `#FFFFFF` |
 * | 右下柔影 / 凹陷描边 | `outline` | `#D9D9D9` |
 * | 凹陷面 | `surfaceContainerHigh` | `#E8E8E8` |
 *
 * 注：miuix 的 `Colors` 没有 shadow 字段，柔影只能从 `outline` 派生。
 */
object Neu {

    /** 默认圆角（与主题无关的纯常量） */
    val defaultCorner: Dp = 22.dp
}

/**
 * 凸起拟态表面：右下柔影 + 左上→右下渐变描边。
 *
 * @param shape 表面形状（决定阴影与描边轮廓）
 * @param elevation 阴影高度，越大越"浮"
 * @param base 表面底色，默认取主题 `surface`（与页面底色同色，拟态精髓）
 */
@Composable
fun Modifier.neuRaised(
    shape: Shape = RoundedCornerShape(Neu.defaultCorner),
    elevation: Dp = 6.dp,
    base: Color = MiuixTheme.colorScheme.surface,
): Modifier {
    val highlight = MiuixTheme.colorScheme.surfaceContainer
    val shadow = MiuixTheme.colorScheme.outline
    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = shadow,
            spotColor = shadow,
        )
        .background(base, shape)
        .border(
            width = 1.dp,
            brush = Brush.linearGradient(listOf(highlight, shadow)),
            shape = shape,
        )
}

/**
 * 凹陷拟态表面：深一档底色 + 反向渐变描边（左上暗、右下亮），
 * 模拟光线射入凹槽的内阴影效果。常用于选中态、输入槽、图标槽。
 *
 * @param base 凹陷面底色，默认取主题 `surfaceContainerHigh`（比基色深半档）
 */
@Composable
fun Modifier.neuPressed(
    shape: Shape = RoundedCornerShape(Neu.defaultCorner),
    base: Color = MiuixTheme.colorScheme.surfaceContainerHigh,
): Modifier {
    val highlight = MiuixTheme.colorScheme.surfaceContainer
    val pressedDark = MiuixTheme.colorScheme.outline
    return this
        .background(base, shape)
        .border(
            width = 1.dp,
            brush = Brush.linearGradient(listOf(pressedDark, highlight)),
            shape = shape,
        )
}
