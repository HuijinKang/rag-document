package com.khj.ragdocument.slack.presentation

import com.khj.ragdocument.slack.application.SlackFacade
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/slack")
class SlackEventController(
    private val slackFacade: SlackFacade,
) {

    @PostMapping("/events")
    fun handleEvent(@RequestBody payload: Map<String, Any>): ResponseEntity<Any> {
        val response = slackFacade.handleEvent(payload)
        return ResponseEntity.ok(response ?: "ok")
    }
}
