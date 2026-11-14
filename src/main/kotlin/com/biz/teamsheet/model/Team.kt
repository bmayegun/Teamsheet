package com.biz.teamsheet.model

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "team")
open class Team (
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long,

    var players: MutableList<Player> = mutableListOf(),
){

    fun getRating(): Int{
        return players.sumOf { it.rating ?: 0 }
    }
}