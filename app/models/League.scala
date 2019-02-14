package models

import java.sql.Timestamp

import org.squeryl.KeyedEntity
import play.api.libs.json._

import entry.SquerylEntrypointForMyApp._
import utils.Formatter.timestampFormatFactory

class League(
              var name: String,
              val apiKey: String, // the api user/platform that created the league
              val gameId: Option[Long],
              var isPrivate: Boolean,
              var tournamentId: Long,
              var pickeeDescription: String,
              var periodDescription: String,
              var transferLimit: Option[Int],
              var transferWildcard: Boolean,
              var startingMoney: BigDecimal,
              val teamSize: Int,
              var transferDelayMinutes: Int = 0, // Only applies for when period 1 has started
              var transferOpen: Boolean = false,
              var transferBlockedDuringPeriod: Boolean = false,
              var url: String = "",
              var currentPeriodId: Option[Long] = None,
            ) extends KeyedEntity[Long] {
  val id: Long = 0

  lazy val users = AppDB.leagueUserTable.left(this)
  lazy val pickees = AppDB.leagueToPickee.left(this)
  //statFields
  def statFields = from(AppDB.leagueToLeagueStatField.left(this))(select(_)).toList
  def factionTypes = from(AppDB.leagueToFactionType.left(this))(select(_)).toList
  def periods = from(AppDB.leagueToPeriod.left(this))(select(_)).toList
  def firstPeriod = from(AppDB.leagueToPeriod.left(this))(p => where(p.value === 1) select(p)).single
  def lastPeriod = from(AppDB.leagueToPeriod.left(this))(p => where(p.nextPeriodId isNull) select(p)).single
  def currentPeriod: Option[Period] = this.currentPeriodId.map(cp => AppDB.periodTable.lookup(cp).get)
  def started = this.currentPeriodId.nonEmpty
  def ended = this.lastPeriod.ended
  def prize: Option[LeaguePrize] = from(AppDB.leagueToLeaguePrize.left(this))(select(_)).singleOption

  // If a class has an Option[] field, it becomes mandatory to implement a zero argument constructor
  def this() = this("", "AAA", Some(1), false, 0, "", "", None, false, BigDecimal.decimal(50.0), 5)

}

class LeagueStatField(
                       val leagueId: Long,
                       val name: String  // Index this
                     ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class LeaguePrize(
                   val leagueId: Long,
                   var description: String,
                   var email: String
                 ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class FactionType(
                  val leagueId: Long,
                  val name: String,
                  var description: String,
                  var max: Option[Int] = None
                 ) extends KeyedEntity[Long] {
  val id: Long = 0
}

case class FactionTypeOut(name: String, description: String, factions: Iterable[Faction])


class Faction(
                   val factionTypeId: Long,
                   val name: String,
                   var max: Int,
                 ) extends KeyedEntity[Long] {
  val id: Long = 0
}

class Period(
            val leagueId: Long,
            val value: Int,
            var start: Timestamp,
            var end: Timestamp,
            var multiplier: Double = 1.0,
            val nextPeriodId: Option[Long] = None,
            var ended: Boolean = false,
            ) extends KeyedEntity[Long] {
  val id: Long = 0

  def this() = this(
    0, 0, new Timestamp(System.currentTimeMillis()), new Timestamp(System.currentTimeMillis())
  )
}


object Faction{
  implicit val implicitWrites = new Writes[Faction] {
    def writes(f: Faction): JsValue = {
      Json.obj(
        "name" -> f.name,
        "max" -> f.max
      )
    }
  }
}

object FactionTypeOut{
  implicit val implicitWrites = new Writes[FactionTypeOut] {
    def writes(ft: FactionTypeOut): JsValue = {
      Json.obj(
        "name" -> ft.name,
        "description" -> ft.description,
        "factions" -> ft.factions
      )
    }
  }
}


object LeaguePrize{
  implicit val implicitWrites = new Writes[LeaguePrize] {
    def writes(lp: LeaguePrize): JsValue = {
      Json.obj(
        "description" -> lp.description,
        "email" -> lp.email
      )
    }
  }
}

object Period{
  implicit val timestampFormat = timestampFormatFactory("yyyy-MM-dd HH:mm:ss")
  implicit val implicitWrites = new Writes[Period] {
    def writes(p: Period): JsValue = {
      Json.obj(
        "value" -> p.value,
        "start" -> p.start,
        "end" -> p.end,
        "multiplier" -> p.multiplier
      )
    }
  }
}

object FactionType{
  implicit val implicitWrites = new Writes[FactionType] {
    def writes(ft: FactionType): JsValue = {
      Json.obj(
        "name" -> ft.name,
        "description" -> ft.description,
      )
    }
  }
}

object League{
  implicit val timestampFormat = timestampFormatFactory("yyyy-MM-dd HH:mm:ss")
  implicit val implicitWrites = new Writes[League] {
    def writes(league: League): JsValue = {
      Json.obj(
        "id" -> league.id,
        "name" -> league.name,
        "gameId" -> league.gameId,
        "tournamentId" -> league.tournamentId,
        "isPrivate" -> league.isPrivate,
        "pickeeDescription" -> league.pickeeDescription,
        "periodDescription" -> league.periodDescription,
        "teamSize" -> league.teamSize,
        "transferLimit" -> league.transferLimit,
        "transferWildcard" -> league.transferWildcard,
        "startingMoney" -> league.startingMoney,
        "transferOpen" -> league.transferOpen,
        "transferDelayMinutes" -> league.transferDelayMinutes,
        "transferBlockedDuringPeriod" -> league.transferBlockedDuringPeriod,
        "url" -> league.url
      )
    }
  }
}
