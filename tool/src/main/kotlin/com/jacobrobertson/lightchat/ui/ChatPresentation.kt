package com.jacobrobertson.lightchat.ui

import com.jacobrobertson.lightchat.data.MessageEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ThreadSummary(
    val name: String,
    val first: MessageEntity,
    val latest: MessageEntity,
    val count: Int,
)

fun summarizeThreads(messages: List<MessageEntity>): List<ThreadSummary> =
    messages.groupBy { it.thread }.map { (name, entries) ->
        val sorted = entries.sortedWith(compareBy<MessageEntity> { it.createdAt }.thenBy { it.name })
        ThreadSummary(name, sorted.first(), sorted.last(), sorted.size)
    }.sortedWith(compareByDescending<ThreadSummary> { it.latest.createdAt }.thenBy { it.name })

fun displayTime(time: Long): String =
    DateTimeFormatter.ofPattern("MMM d, HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(time))
