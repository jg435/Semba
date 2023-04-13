package com.simplemobiletools.smsmessenger.helpers

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.simplemobiletools.commons.extensions.getProperPrimaryColor
import com.simplemobiletools.commons.helpers.SimpleContactsHelper
import com.simplemobiletools.commons.helpers.isNougatPlus
import com.simplemobiletools.commons.helpers.isOreoPlus
import com.simplemobiletools.smsmessenger.R
import com.simplemobiletools.smsmessenger.activities.ThreadActivity
import com.simplemobiletools.smsmessenger.extensions.config
import com.simplemobiletools.smsmessenger.messaging.isShortCodeWithLetters
import com.simplemobiletools.smsmessenger.receivers.DirectReplyReceiver
import com.simplemobiletools.smsmessenger.receivers.MarkAsReadReceiver


class NotificationHelper(private val context: Context) {

    private val applicationContext: Context = context.applicationContext
    private val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val user = Person.Builder()
        .setName(context.getString(R.string.me))
        .build()

//    data class SentimentScore(
//        val alert: String,
//        val positive: Float,
//        val negative: Float,
//        val neutral: Float,
//        val love: Float,
//        val joy: Float,
//        val sadness: Float,
//        val anger: Float,
//        val surprise: Float,
//    )
//
//    @SuppressLint("NewApi")
//    fun readAlertSoundSentimentScores() {
//        val bufferedReader = Files.newBufferedReader(Paths.get("/resources/students.csv"))
//        val csvParser = CSVParser(bufferedReader, CSVFormat.DEFAULT)
////        for (csvRecord in csvParser) {
////            val studentId = csvRecord.get(0);
////            val studentName = csvRecord.get(1);
////            val studentLastName = csvRecord.get(2);
////            var studentScore = csvRecord.get(3);
////            println(SentimentScore(studentId, studentName, studentLastName, studentScore));
////        }
//    }


    @SuppressLint("NewApi")
    fun showMessageNotification(address: String, body: String, threadId: Long, bitmap: Bitmap?, sender: String?, alertOnlyOnce: Boolean = false) {

        // 1. Send body to python API as input
        // 2. Get audio filename as output (variable str)

        val py: Python = Python.getInstance()
        val pyo: PyObject = py.getModule("ensemble")
        val obj: PyObject = pyo.callAttr("pair_output_with_alert", body)
        val str: String = obj.toString()

        var channelId = body
        Log.i("Channel name", str)

        val notificationId = threadId.hashCode()
        val contentIntent = Intent(context, ThreadActivity::class.java).apply {
            putExtra(THREAD_ID, threadId)
        }
        val contentPendingIntent =
            PendingIntent.getActivity(context, notificationId, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

        val markAsReadIntent = Intent(context, MarkAsReadReceiver::class.java).apply {
            action = MARK_AS_READ
            putExtra(THREAD_ID, threadId)
        }
        val markAsReadPendingIntent =
            PendingIntent.getBroadcast(context, notificationId, markAsReadIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

        var replyAction: NotificationCompat.Action? = null
        if (isNougatPlus() && !isShortCodeWithLetters(address)) {
            val replyLabel = context.getString(R.string.reply)
            val remoteInput = RemoteInput.Builder(REPLY)
                .setLabel(replyLabel)
                .build()

            val replyIntent = Intent(context, DirectReplyReceiver::class.java).apply {
                putExtra(THREAD_ID, threadId)
                putExtra(THREAD_NUMBER, address)
            }

            val replyPendingIntent =
                PendingIntent.getBroadcast(
                    context.applicationContext,
                    notificationId,
                    replyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
            replyAction = NotificationCompat.Action.Builder(R.drawable.ic_send_vector, replyLabel, replyPendingIntent)
                .addRemoteInput(remoteInput)
                .build()
        }

        val largeIcon = bitmap ?: if (sender != null) {
            SimpleContactsHelper(context).getContactLetterIcon(sender)
        } else {
            null
        }

        //TODO: Change channelID based on text body

        val builder = NotificationCompat.Builder(context, channelId).apply {
            when (context.config.lockScreenVisibilitySetting) {
                LOCK_SCREEN_SENDER_MESSAGE -> {
                    setLargeIcon(largeIcon)
                    setStyle(getMessagesStyle(address, body, notificationId, sender))
                }
                LOCK_SCREEN_SENDER -> {
                    setContentTitle(sender)
                    setLargeIcon(largeIcon)
                    val summaryText = context.getString(R.string.new_message)
                    setStyle(NotificationCompat.BigTextStyle().setSummaryText(summaryText).bigText(body))
                }
            }

            color = context.getProperPrimaryColor()
            setSmallIcon(R.drawable.ic_messenger)
            setContentIntent(contentPendingIntent)
            priority = NotificationCompat.PRIORITY_MAX
            setDefaults(Notification.DEFAULT_LIGHTS)
            setCategory(Notification.CATEGORY_MESSAGE)
            setAutoCancel(true)
            setOnlyAlertOnce(alertOnlyOnce)
        }

        if (replyAction != null && context.config.lockScreenVisibilitySetting == LOCK_SCREEN_SENDER_MESSAGE) {
            builder.addAction(replyAction)
        }

        builder.addAction(R.drawable.ic_check_vector, context.getString(R.string.mark_as_read), markAsReadPendingIntent)
            .setChannelId(channelId)

        notificationManager.notify(notificationId, builder.build())
    }

    @SuppressLint("NewApi")
    fun showSendingFailedNotification(recipientName: String, threadId: Long) {
        maybeCreateChannel(name = context.getString(R.string.message_not_sent_short))

        val notificationId = generateRandomId().hashCode()
        val intent = Intent(context, ThreadActivity::class.java).apply {
            putExtra(THREAD_ID, threadId)
        }
        val contentPendingIntent = PendingIntent.getActivity(context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

        val summaryText = String.format(context.getString(R.string.message_sending_error), recipientName)
        val largeIcon = SimpleContactsHelper(context).getContactLetterIcon(recipientName)
        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
            .setContentTitle(context.getString(R.string.message_not_sent_short))
            .setContentText(summaryText)
            .setColor(context.getProperPrimaryColor())
            .setSmallIcon(R.drawable.ic_messenger)
            .setLargeIcon(largeIcon)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summaryText))
            .setContentIntent(contentPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(Notification.DEFAULT_LIGHTS)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setChannelId(NOTIFICATION_CHANNEL)

        notificationManager.notify(notificationId, builder.build())
    }

    fun createChannels() {

        if (isOreoPlus()) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setLegacyStreamType(AudioManager.STREAM_NOTIFICATION)
                .build()

            var sentimentChannels = listOf("angry", "anxious", "boom", "boss", "confused", "default_ios", "disappointed", "disappointed3", "disappointed4", "disappointed5", "disappointed2", "emergency", "emergency2", "emergency3", "exciting", "funny", "happy", "happy2", "happy3", "important_ominous", "important", "negative", "negative2", "negative3", "negative4", "ominous", "ominous2", "positive", "positive2", "positive3", "positive4", "positive5", "positive6", "quick", "sad", "sad2", "serious", "serious2", "solemn")

            for (channel in sentimentChannels) {
                val channelSound = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.packageName + "/raw/" + channel)
                notificationManager.createNotificationChannel(
                    NotificationChannel(channel, channel, NotificationManager.IMPORTANCE_HIGH)
                        .apply {
                            setBypassDnd(false)
                            setSound(channelSound, audioAttributes)
                            enableLights(false)
                            enableVibration(true)
                            setShowBadge(false)
                        }
                )
            }
        }
    }

    private fun maybeCreateChannel(name: String) {
        if (isOreoPlus()) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setLegacyStreamType(AudioManager.STREAM_NOTIFICATION)
                .build()

            val id = NOTIFICATION_CHANNEL
            val importance = IMPORTANCE_HIGH
            val channelSound = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.packageName + "/raw/" + "disappointed")
            NotificationChannel(id, name, importance).apply {
                setBypassDnd(false)
                enableLights(true)
                setSound(channelSound, audioAttributes)
                enableVibration(true)
                notificationManager.createNotificationChannel(this)
            }
        }
    }

    private fun getMessagesStyle(address: String, body: String, notificationId: Int, name: String?): NotificationCompat.MessagingStyle {
        val sender = if (name != null) {
            Person.Builder()
                .setName(name)
                .setKey(address)
                .build()
        } else {
            null
        }

        return NotificationCompat.MessagingStyle(user).also { style ->
            getOldMessages(notificationId).forEach {
                style.addMessage(it)
            }
            val newMessage = NotificationCompat.MessagingStyle.Message(body, System.currentTimeMillis(), sender)
            style.addMessage(newMessage)
        }
    }

    private fun getOldMessages(notificationId: Int): List<NotificationCompat.MessagingStyle.Message> {
        if (!isNougatPlus()) {
            return emptyList()
        }
        val currentNotification = notificationManager.activeNotifications.find { it.id == notificationId }
        return if (currentNotification != null) {
            val activeStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(currentNotification.notification)
            activeStyle?.messages.orEmpty()
        } else {
            emptyList()
        }
    }
}
