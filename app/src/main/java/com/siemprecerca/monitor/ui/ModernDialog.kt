package com.siemprecerca.monitor.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.siemprecerca.monitor.R

enum class DialogType { ERROR, SUCCESS, WARNING, INFO }

class ModernDialog(private val context: Context) {

    private var dialog: Dialog? = null

    fun show(
        type: DialogType,
        title: String,
        message: String,
        primaryText: String = "Entendido",
        secondaryText: String? = null,
        onPrimary: (() -> Unit)? = null,
        onSecondary: (() -> Unit)? = null,
        autoDismissMs: Long = 0,
    ) {
        dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(R.layout.dialog_modern)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(
                (context.resources.displayMetrics.widthPixels * 0.88).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            window?.setGravity(Gravity.CENTER)
            window?.setDimAmount(0.6f)
            setCancelable(true)
        }

        val iconRes = when (type) {
            DialogType.ERROR -> R.drawable.ic_dialog_error
            DialogType.SUCCESS -> R.drawable.ic_dialog_success
            DialogType.WARNING -> R.drawable.ic_dialog_warning
            DialogType.INFO -> R.drawable.ic_dialog_info
        }

        dialog?.findViewById<ImageView>(R.id.dialogIcon)?.setImageResource(iconRes)
        dialog?.findViewById<TextView>(R.id.dialogTitle)?.text = title
        dialog?.findViewById<TextView>(R.id.dialogMessage)?.text = message

        dialog?.findViewById<Button>(R.id.dialogBtnPrimary)?.apply {
            text = primaryText
            setOnClickListener {
                dialog?.dismiss()
                onPrimary?.invoke()
            }
        }

        if (secondaryText != null) {
            dialog?.findViewById<Button>(R.id.dialogBtnSecondary)?.apply {
                visibility = View.VISIBLE
                text = secondaryText
                setOnClickListener {
                    dialog?.dismiss()
                    onSecondary?.invoke()
                }
            }
        }

        dialog?.show()

        if (autoDismissMs > 0) {
            Handler(Looper.getMainLooper()).postDelayed({
                dialog?.dismiss()
            }, autoDismissMs)
        }
    }

    fun dismiss() {
        dialog?.dismiss()
    }

    companion object {
        fun error(context: Context, title: String, message: String, onDismiss: (() -> Unit)? = null) {
            ModernDialog(context).show(DialogType.ERROR, title, message, onPrimary = onDismiss)
        }

        fun success(context: Context, title: String, message: String, autoDismissMs: Long = 3000) {
            ModernDialog(context).show(DialogType.SUCCESS, title, message, autoDismissMs = autoDismissMs)
        }

        fun warning(context: Context, title: String, message: String, onDismiss: (() -> Unit)? = null) {
            ModernDialog(context).show(DialogType.WARNING, title, message, onPrimary = onDismiss)
        }

        fun info(context: Context, title: String, message: String, onDismiss: (() -> Unit)? = null) {
            ModernDialog(context).show(DialogType.INFO, title, message, onPrimary = onDismiss)
        }

        fun confirm(
            context: Context,
            title: String,
            message: String,
            confirmText: String = "Si",
            cancelText: String = "No",
            onConfirm: () -> Unit,
        ) {
            ModernDialog(context).show(
                DialogType.WARNING,
                title,
                message,
                primaryText = confirmText,
                secondaryText = cancelText,
                onPrimary = onConfirm,
            )
        }
    }
}
