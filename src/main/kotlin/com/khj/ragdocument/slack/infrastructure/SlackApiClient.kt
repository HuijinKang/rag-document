package com.khj.ragdocument.slack.infrastructure

import com.slack.api.Slack
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class SlackApiClient(
    @Value("\${slack.bot-token}") private val botToken: String,
) {
    private val slack = Slack.getInstance()

    fun sendMessage(channel: String, text: String) {
        slack.methods(botToken).chatPostMessage { req ->
            req.channel(channel)
                .text(text)
                .mrkdwn(true)
        }
    }

    fun downloadFile(fileUrl: String): ByteArray {
        val connection = java.net.URI(fileUrl).toURL().openConnection()
        connection.setRequestProperty("Authorization", "Bearer $botToken")
        return connection.getInputStream().readBytes()
    }
}
