package com.biz.teamsheet.model

data class Player(
    val id: String,
    val name: String,
    val skillRating: Int,
    val gender: String? = null,
    val positions: List<Position> = emptyList(),
)
