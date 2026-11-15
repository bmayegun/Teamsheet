package com.biz.teamsheet.controller

import com.biz.teamsheet.model.TeamRequest
import com.biz.teamsheet.service.TeamService
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/teams")
class TeamController(private val service: TeamService) {
    @PostMapping("/generate")
    fun generate(@RequestBody request: TeamRequest) = service.generate(request)

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidRequest(error: IllegalArgumentException) =
        mapOf("message" to (error.message ?: "Invalid team settings."))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun malformedRequest() =
        mapOf("message" to "Check that player details, positions and team settings use valid values.")
}
