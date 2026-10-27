package com.mcpanel.ui

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mcpanel.R

object Ui {

    fun toast(context: Context, message: String) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun confirm(
        context: Context,
        title: String,
        message: String,
        positive: String = "确定",
        negative: String = "取消",
        onPositive: () -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onPositive() }
            .setNegativeButton(negative, null)
            .show()
    }

    fun input(
        context: Context,
        title: String,
        hint: String,
        initial: String = "",
        numeric: Boolean = false,
        onOk: (String) -> Unit
    ) {
        val input = EditText(context).apply {
            this.hint = hint
            setText(initial)
            setSelection(text?.length ?: 0)
            setTextColor(ContextCompatColor(context, R.color.text))
            setHintTextColor(ContextCompatColor(context, R.color.text_faint))
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            setPadding(dp(context, 20f), dp(context, 8f), dp(context, 20f), dp(context, 8f))
        }
        val container = FrameLayout(context).apply {
            setPadding(0, dp(context, 8f), 0, 0)
            addView(input, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("确定") { _, _ -> onOk(input.text?.toString()?.trim() ?: "") }
            .setNegativeButton("取消", null)
            .show()
    }

    fun list(
        context: Context,
        title: String,
        items: List<String>,
        onPick: (Int) -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which -> onPick(which) }
            .setNegativeButton("取消", null)
            .show()
    }

    fun prettyPath(path: String): String {
        return path
            .replace("/storage/emulated/0", "内部存储")
            .replace("/sdcard", "内部存储")
    }

    fun statusColor(context: Context, status: String): Int {
        val res = when (status) {
            "running" -> R.color.success
            "starting", "stopping" -> R.color.warning
            "crashed" -> R.color.danger
            else -> R.color.text_faint
        }
        return androidx.core.content.ContextCompat.getColor(context, res)
    }

    private fun ContextCompatColor(context: Context, resId: Int): Int =
        androidx.core.content.ContextCompat.getColor(context, resId)

    fun hideKeyboard(activity: Activity) {
        try {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        } catch (_: Throwable) {
        }
    }

    class ProgressDialog(
        private val dialog: AlertDialog,
        private val messageView: TextView,
        private val detailView: TextView,
        private val progressBar: ProgressBar
    ) {
        var canceled: Boolean = false
            private set
        private var onCancel: (() -> Unit)? = null
        private var lastPercent = -1

        fun setStage(message: String) {
            messageView.text = message
        }

        fun setProgress(percent: Int) {
            val p = percent.coerceIn(0, 100)
            if (p == lastPercent) return
            lastPercent = p
            if (p <= 0) {
                progressBar.isIndeterminate = true
            } else {
                progressBar.isIndeterminate = false
                progressBar.progress = p
            }
        }

        fun setDetail(text: String) {
            detailView.text = text
        }

        fun setCancelHandler(handler: () -> Unit) {
            onCancel = handler
        }

        fun dismiss() {
            try {
                dialog.dismiss()
            } catch (_: Throwable) {
            }
        }

        fun isShowing(): Boolean = dialog.isShowing

        fun cancel() {
            canceled = true
            onCancel?.invoke()
        }
    }

    fun progress(context: Context, title: String, cancelable: Boolean = true): ProgressDialog {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_progress, null)
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val messageView = view.findViewById<TextView>(R.id.message)
        val detailView = view.findViewById<TextView>(R.id.detail)
        val progressBar = view.findViewById<ProgressBar>(R.id.progress)
        val cancelButton = view.findViewById<View>(R.id.btnCancel)
        messageView.text = title
        progressBar.isIndeterminate = true
        val pd = ProgressDialog(dialog, messageView, detailView, progressBar)
        cancelButton.visibility = if (cancelable) View.VISIBLE else View.GONE
        cancelButton.setOnClickListener { pd.cancel() }
        dialog.setOnDismissListener { }
        dialog.show()
        dialog.window?.setLayout(
            context.resources.displayMetrics.widthPixels - dp(context, 48f),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        return pd
    }

    fun dialogWidth(context: Context, dialog: DialogInterface, widthDp: Int) {
        try {
            val alert = dialog as AlertDialog
            alert.window?.setLayout(
                context.resources.displayMetrics.widthPixels - dp(context, 32f),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            alert.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        } catch (_: Throwable) {
        }
    }
}
