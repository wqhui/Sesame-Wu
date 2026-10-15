package com.sesame.wu.ui.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Column {
                Text(title, color = MiuixTheme.colorScheme.onBackground)
                Spacer(Modifier.height(8.dp))
                Text(text, color = MiuixTheme.colorScheme.onBackground)
                Spacer(Modifier.height(12.dp))
                DialogActionButtons(
                    onConfirm = onConfirm,
                    onDismiss = onDismiss,
                    confirmText = "确定",
                )
            }
        }
    }
}

/**
 * 弹窗底部按钮组：取消（次要）+ 确认（主要）。
 *
 * 两个按钮都是 miuix 的 [TextButton]，但确认按钮套用
 * [ButtonDefaults.textButtonColorsPrimary]（主色底 + 白字），取消按钮保持默认灰底，
 * 形成明确的视觉层级——否则两者外观完全一致，用户分不清哪个是主操作。
 *
 * @param confirmText 确认按钮文案（如「保存」「确定」）
 * @param cancelText  取消按钮文案
 */
@Composable
fun DialogActionButtons(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "保存",
    cancelText: String = "取消",
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(text = cancelText, onClick = onDismiss)
        Spacer(Modifier.width(8.dp))
        TextButton(
            text = confirmText,
            onClick = onConfirm,
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}
