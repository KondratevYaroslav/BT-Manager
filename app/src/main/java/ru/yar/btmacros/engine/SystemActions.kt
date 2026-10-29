package ru.yar.btmacros.engine

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import ru.yar.btmacros.App
import ru.yar.btmacros.Notifications
import ru.yar.btmacros.model.MediaKey
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/** Медиа-кнопки: работают из фона, управляют текущим плеером. */
object MediaKeys {
    fun send(ctx: Context, key: MediaKey) {
        val am = ctx.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key.code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key.code))
    }
}

/** Запуск приложений, таймеров и будильников. */
object Launcher {
    data class AppInfo(val packageName: String, val label: String)

    fun listApps(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(intent, 0)
            .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.packageName }
            .filter { it.packageName != ctx.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun canStartFromBackground(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun openApp(ctx: Context, pkg: String, label: String): String {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "приложение не найдено"
        return start(ctx, intent, "Открыть $label")
    }

    fun setTimer(ctx: Context, seconds: Int, label: String): String =
        start(ctx, Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label) },
            "Запустить таймер")

    fun setAlarm(ctx: Context, hour: Int, minute: Int, label: String): String =
        start(ctx, Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label) },
            "Поставить будильник")

    /**
     * Android запрещает открывать экраны из фона. Если приложению разрешено
     * «Поверх других окон» или оно сейчас на экране — открываем сразу,
     * иначе показываем уведомление, по нажатию на которое действие выполнится.
     */
    private fun start(ctx: Context, intent: Intent, title: String): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
            return "на телефоне нет приложения для этого действия"
        }
        if (canStartFromBackground(ctx) || App.instance.uiVisible) {
            try {
                ctx.startActivity(intent)
                return "OK"
            } catch (e: ActivityNotFoundException) {
                return "нет приложения для этого действия"
            } catch (e: Exception) {
                // упадём в уведомление ниже
            }
        }
        val pi = PendingIntent.getActivity(
            ctx, (System.currentTimeMillis() % 100000).toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        Notifications.actionPrompt(ctx, title, "Нажмите, чтобы выполнить", pi)
        return "нет разрешения на запуск из фона — показал уведомление"
    }
}

/** Отправка писем через SMTP (Яндекс, Gmail, Mail.ru и т.п.). */
object Mailer {
    data class Config(
        val host: String,
        val port: Int,
        val ssl: Boolean,
        val user: String,
        val password: String,
        val from: String,
        val defaultTo: String,
    ) {
        val isComplete get() = host.isNotBlank() && user.isNotBlank() && password.isNotBlank()
    }

    fun load(ctx: Context): Config {
        val p = ctx.getSharedPreferences("mail", Context.MODE_PRIVATE)
        return Config(
            host = p.getString("host", "smtp.yandex.ru")!!,
            port = p.getInt("port", 465),
            ssl = p.getBoolean("ssl", true),
            user = p.getString("user", "")!!,
            password = p.getString("password", "")!!,
            from = p.getString("from", "")!!,
            defaultTo = p.getString("to", "")!!,
        )
    }

    fun save(ctx: Context, c: Config) {
        ctx.getSharedPreferences("mail", Context.MODE_PRIVATE).edit()
            .putString("host", c.host.trim())
            .putInt("port", c.port)
            .putBoolean("ssl", c.ssl)
            .putString("user", c.user.trim())
            .putString("password", c.password)
            .putString("from", c.from.trim())
            .putString("to", c.defaultTo.trim())
            .apply()
    }

    /** Блокирующий вызов — только из фонового потока. Возвращает null при успехе или текст ошибки. */
    fun send(c: Config, to: String, subject: String, body: String): String? {
        if (!c.isComplete) return "почта не настроена (Настройки → Почта)"
        val recipient = to.ifBlank { c.defaultTo }.ifBlank { c.user }
        return try {
            val props = Properties().apply {
                put("mail.smtp.host", c.host)
                put("mail.smtp.port", c.port.toString())
                put("mail.smtp.auth", "true")
                put("mail.smtp.connectiontimeout", "15000")
                put("mail.smtp.timeout", "15000")
                put("mail.smtp.writetimeout", "15000")
                if (c.ssl) put("mail.smtp.ssl.enable", "true") else put("mail.smtp.starttls.enable", "true")
            }
            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() = PasswordAuthentication(c.user, c.password)
            })
            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(c.from.ifBlank { c.user }))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient))
                setSubject(subject.ifBlank { "BT Макросы" }, "UTF-8")
                setText(body, "UTF-8")
            }
            Transport.send(msg)
            null
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
