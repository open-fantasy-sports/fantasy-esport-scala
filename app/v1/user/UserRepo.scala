package v1.user

import play.api.libs.json.{JsValue, Json, Writes}
import java.sql.Connection
import java.time.LocalDateTime

import javax.inject.{Inject, Singleton}
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext
import anorm._
import anorm.{Macro, RowParser}
import Macro.ColumnNaming
import models._
import play.api.Logger
import play.api.db.Database
import utils.Utils
import v1.league.LeagueRepo
import v1.pickee.PickeeRepo
import v1.team.TeamRepo
import v1.transfer.TransferRepo

case class Ranking(userId: Long, username: String, value: Double, ranking: Int, previousRank: Option[Int], team: Option[Iterable[PickeeRow]],
                   showTeam: Boolean = true)

case class LeagueRankings(leagueId: Long, leagueName: String, statField: String, rankings: Iterable[Ranking])

case class LeagueWithUser(league: LeagueRow, info: UserRow)

case class RankingRow(
                       externalUserId: Long, username: String, userId: Long, value: Double, previousRank: Option[Int],
                       internalPickeeId: Option[Long], externalPickeeId: Option[Long],
                       pickeeName: Option[String], price: Option[BigDecimal], active: Option[Boolean], ranking: Int
                     )

case class TeamWithPeriod(team: Iterable[CardOut], period: Int)
object TeamWithPeriod {
  implicit val implicitWrites = new Writes[TeamWithPeriod] {
    def writes(x: TeamWithPeriod): JsValue = {
      Json.obj(
        "team" -> x.team,
        "period" -> x.period
      )
    }
  }
}

object LeagueWithUser {
  implicit val implicitWrites = new Writes[LeagueWithUser] {
    def writes(x: LeagueWithUser): JsValue = {
      Json.obj(
        "league" -> x.league,
        "userInfo" -> x.info
      )
    }
  }
}

object Ranking{
  implicit val implicitWrites = new Writes[Ranking] {
    def writes(ranking: Ranking): JsValue = {
      Json.obj(
        "userId" -> ranking.userId,
        "username" -> ranking.username,
        "value" -> Utils.trunc(ranking.value, 1),
        "rank" -> ranking.ranking,
        "previousRank" -> ranking.previousRank,
        "team" ->  ranking.team
      )
    }
  }
}

object LeagueRankings{
  implicit val implicitWrites = new Writes[LeagueRankings] {
    def writes(leagueRank: LeagueRankings): JsValue = {
      Json.obj(
        "leagueId" -> leagueRank.leagueId,
        "leagueName" -> leagueRank.leagueName,
        "rankings" -> leagueRank.rankings,
        "statField" -> leagueRank.statField,
      )
    }
  }
}

case class DetailedUser(user: UserRow, team: Option[Iterable[TeamWithPeriod]], scheduledTransfers: Option[ScheduledChangesOut], stats: Option[Map[String, Double]])

object DetailedUser{
  implicit val implicitWrites = new Writes[DetailedUser] {
    def writes(x: DetailedUser): JsValue = {
      Json.obj(
        "user" -> x.user,
        "team" -> x.team,
        "scheduledTransfers" -> x.scheduledTransfers,
        "stats" -> x.stats.map(_.mapValues(Utils.trunc(_, 1)))
      )
    }
  }
}


class UserExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait UserRepo{
  def update(userId: Long, input: UpdateUserFormInput)(implicit c: Connection): Unit
  def get(leagueId: Long, externalUserId: Long)(implicit c: Connection): Option[UserRow]
  def detailedUser(
                          user: UserRow, showTeam: Boolean, showScheduledTransfers: Boolean,
                          stats: Boolean, periods: Iterable[Int], currentPeriod: Option[Int])(implicit c: Connection): DetailedUser
  def getAllUsersForLeague(leagueId: Long)(implicit c: Connection): Iterable[UserRow]
  def insertUser(league: LeagueRow, userId: Long, username: String)(implicit c: Connection): UserRow
  def insertUserStat(statFieldId: Long, userId: Long)(implicit c: Connection): Long
  def insertUserStatDaily(userStatId: Long, period: Option[Int])(implicit c: Connection): Long
  def updateFromTransfer(
              userId: Long, money: BigDecimal, remainingTransfers: Option[Int],
              appliedWildcard: Boolean
            )(implicit c: Connection): Unit
  def getRankings(
                   league: LeagueRow, statFieldId: Long, period: Option[Int], userIds: Option[Array[Long]],
                   secondaryOrdering: Option[List[Long]], showTeam: Boolean
                 )(implicit c: Connection): LeagueRankings
  def getUserStats(
                          leagueId: Option[Long], userId: Option[Long], statFieldId: Option[Long], period: Option[Int],
                          orderByValue: Boolean
                        )(implicit c: Connection): Iterable[UserStatDailyRow]
  def getUserStatsAndTeam(
                           leagueId: Long, statFieldId: Long, period: Option[Int],
                                 secondaryOrdering: Option[List[Long]])(implicit c: Connection): Iterable[RankingRow]
  def updatePreviousRank(userId: Long, statFieldId: Long, previousRank: Int)(implicit c: Connection): Unit
  def joinUser(externalUserId: Long, username: String, league: LeagueRow): UserRow
  def userInLeague(externalUserId: Long, leagueId: Long)(implicit c: Connection): Boolean
  def updateHistoricRanks(leagueId: Long)(implicit c: Connection)
  def setlateEntryLockTs(userId: Long)(implicit c: Connection): Int
}

@Singleton
class UserRepoImpl @Inject()(db: Database, transferRepo: TransferRepo, teamRepo: TeamRepo, pickeeRepo: PickeeRepo)(implicit ec: UserExecutionContext, leagueRepo: LeagueRepo) extends UserRepo{
  private val logger = Logger("application")

  override def update(userId: Long, input: UpdateUserFormInput)(implicit c: Connection): Unit = {
    val setString = (input.username, input.externalUserId) match {
      case (Some(username), Some(externalId)) => "set username = {username}, external_user_id = {externalUserId}"
      case (None, Some(externalId)) => "set external_user_id = {externalUserId}"
      case (Some(username), None) => "set username = {username}"
      case (None, None) => ""
    }
    SQL(
      s"update useru $setString where external_user_id = $userId returning user_id, username, external_user_id"
    ).on("username" -> input.username, "externalUserId" -> input.externalUserId).executeUpdate()
    println("todo return stuff")
  }

  override def get(leagueId: Long, externalUserId: Long)
                          (implicit c: Connection): Option[UserRow] = {
    SQL(s"""select user_id, username, external_user_id, money, entered, remaining_transfers, used_wildcard,
            late_entry_lock_ts
      from useru where league_id = $leagueId and external_user_id = $externalUserId;""").as(UserRow.parser.singleOpt)
  }

  override def detailedUser(
                                   user: UserRow, showTeam: Boolean, showScheduledTransfers: Boolean,
                                   showStats: Boolean, periods: Iterable[Int], currentPeriod: Option[Int])
                           (implicit c: Connection): DetailedUser = {
    val teams = showTeam match {
      case false => None
      case true => {
        Some(periods.map(p => TeamWithPeriod(teamRepo.getUserTeam(user.userId, Some(p)), p)))
      }
    }
    val scheduledTransfers = if (showScheduledTransfers && currentPeriod.isDefined) Some(
      transferRepo.getNextPeriodScheduledChanges(user.userId, currentPeriod.get)
    ) else None
    val stats = if (showStats) {
      Some(getUserStats(
        Option.empty[Long], Some(user.userId), None, None, false
      ).map(x => x.statFieldName -> x.value).toMap)
    }
    else None
    DetailedUser(user, teams, scheduledTransfers, stats)
  }

  override def getAllUsersForLeague(leagueId: Long)(implicit c: Connection): Iterable[UserRow] = {
    SQL("select user_id, username, external_user_id, money, entered, remaining_transfers, used_wildcard, late_entry_lock_ts" +
      "from useru where league_id = $leagueId").as(UserRow.parser.*)
  }

  override def insertUser(league: LeagueRow, externalUserId: Long, username: String)(implicit c: Connection): UserRow = {
    println("inserting league user")
    SQL(
      """
        |insert into useru(league_id, external_user_id, username, money, entered, remaining_transfers, used_wildcard) values
        |({leagueId}, {externalUserId}, {username}, {startingMoney}, {entered},
        | {remainingTransfers}, {usedWildcard}) returning user_id;
      """.stripMargin).on(
      "leagueId" -> league.leagueId, "externalUserId" -> externalUserId, "startingMoney" -> league.startingMoney,
      "entered" -> LocalDateTime.now(), "remainingTransfers" -> league.transferLimit, "username" -> username,
      // dont give wildcard to people who join league late
      "usedWildcard" -> (!league.transferWildcard.getOrElse(false) ||
        (leagueRepo.isStarted(league) && league.noWildcardForLateRegister.getOrElse(false)))
    ).executeInsert().get
    println("executed insert league user")
    get(league.leagueId, externalUserId).get
  }

  override def insertUserStat(statFieldId: Long, userId: Long)(implicit c: Connection): Long = {
    SQL(
      s"insert into user_stat(stat_field_id, user_id, previous_rank) VALUES ($statFieldId, $userId, 1) returning user_stat_id;"
    ).executeInsert().get
  }

  override def insertUserStatDaily(userStatId: Long, period: Option[Int])(implicit c: Connection): Long = {
    SQL(
      """insert into user_stat_period(user_stat_id, period, value) VALUES
        |({userStatId}, {period}, 0) returning user_stat_period_id;""".stripMargin
    ).on("userStatId" -> userStatId, "period" -> period).executeInsert().get
  }

  override def updateFromTransfer(
                       userId: Long, money: BigDecimal, remainingTransfers: Option[Int],
                       appliedWildcard: Boolean
                     )(implicit c: Connection): Unit = {
    val usedWildcardSet = if (appliedWildcard) ", used_wildcard = true" else ""
    SQL(s"""update useru set money = {money}, remaining_transfers = {remainingTransfers} $usedWildcardSet where user_id = {userId}""")
      .on("money" -> money, "remainingTransfers" -> remainingTransfers, "userId" -> userId).executeUpdate()
  }

  override def getRankings(
                            league: LeagueRow, statFieldId: Long, period: Option[Int],
                            userIds: Option[Array[Long]], secondaryOrdering: Option[List[Long]], showTeam: Boolean
                          )(implicit c: Connection): LeagueRankings = {
    println(s"getrankings: userIds: ${userIds.map(_.toList.mkString(",")).getOrElse("None")}")
    val qResult = getUserStatsAndTeam(league.leagueId, statFieldId, period, secondaryOrdering).toList
    val filteredByUsers = if (userIds.isDefined) qResult.filter(q => userIds.get.toList.contains(q.externalUserId)) else qResult
    val stats = filteredByUsers.groupBy(_.externalUserId)
    val rankings: Iterable[Ranking] = stats.map({case (externalUserId, rows) => {
      val row = rows.head
      val team = if (showTeam) Some(rows.withFilter(_.internalPickeeId.isDefined).map(v2 => PickeeRow(
        v2.internalPickeeId.get, v2.externalPickeeId.get, v2.pickeeName.get, v2.price.get, v2.active.get)
      )) else None
      Ranking(externalUserId, row.username, row.value, row.ranking, row.previousRank, team)
    }
    }).toList.sortBy(- _.value)

    LeagueRankings(
      league.leagueId, league.leagueName, leagueRepo.getStatFieldName(statFieldId).get, rankings
    )
  }

  override def getUserStats(
                                   leagueId: Option[Long], userId: Option[Long], statFieldId: Option[Long],
                                   period: Option[Int], orderByValue: Boolean
                                 )(implicit c: Connection): Iterable[UserStatDailyRow] = {
    // todo assert either league or league user id non empty XOR
    // they are both nullable so that this func can work for either getting all league-users, or just one
    logger.debug("getUserStats")
    val periodFilter = if (period.isEmpty) "is null" else s"= ${period.get}"
    val statFieldFilter = if (statFieldId.isEmpty) "" else s"sf.stat_field_id = ${statFieldId.get} and"
    val userFilter = if (userId.isEmpty) "" else s"u.user_id = ${userId.get} and"
    val leagueFilter = if (leagueId.isEmpty) "" else s"league_id = ${leagueId.get} and"
    val orderByValueStr = if (orderByValue) "" else "order by value desc"
    val sql = s"""
                 |select u.user_id, sf.name as stat_field_name, previous_rank, value, period from useru u join stat_field sf using(league_id)
                 |join user_stat us on(sf.stat_field_id = us.stat_field_id and u.user_id = us.user_id)
                 |join user_stat_period using(user_stat_id)
                 |where $leagueFilter $userFilter $statFieldFilter period $periodFilter $orderByValueStr;
                 |
      """.stripMargin
    logger.debug(s"sql: $sql")
    SQL(sql).as(UserStatDailyRow.parser.*)
  }

  override def getUserStatsAndTeam(
                                            leagueId: Long, statFieldId: Long, period: Option[Int],
                                            secondaryOrdering: Option[List[Long]]
                                          )(implicit c: Connection): Iterable[RankingRow] = {
    // Duplicate rankings not possible as gave it an order by userId
    // as have to use dense ranks because of multi rows for each user
    val rankingParser: RowParser[RankingRow] = Macro.namedParser[RankingRow](ColumnNaming.SnakeCase)
    println(period)
    val teamPeriodFilter = if (period.isDefined) "t.timespan @> {period}" else "upper(t.timespan) is NULL"
    val statPeriodFilter = if (period.isDefined) "usp.period = {period}" else "usp.period is NULL"
    val q = secondaryOrdering match {
      case None => s"""select u.external_user_id, u.username, u.user_id, usp.value, us.previous_rank,
                  pickee_id as internal_pickee_id, external_pickee_id, p.pickee_name, p.price, active,
                  dense_rank() OVER (order by value desc, u.user_id) as ranking
                  from useru u
                  join card c using(user_id)
           join team t on (c.card_id = t.card_id and $teamPeriodFilter)
           join pickee p using(pickee_id)
           join user_stat us on (us.user_id = u.user_id and us.stat_field_id = {statFieldId})
           join user_stat_period usp on (usp.user_stat_id = us.user_stat_id and $statPeriodFilter)
           """
      case Some(secondary) => {
        val extraJoins = secondary.map(s =>
          s"""join user_stat us$s on (us$s.user_id = u.user_id and us$s.stat_field_id = $s)
            join user_stat_period usp$s on (usd$s.user_stat_id = us$s.user_stat_id and $statPeriodFilter)
            """).mkString(" ")
        val extraOrder = secondary.map(s => s"lusd$s.value desc,").mkString(" ")
        s"""select u.external_user_id, u.username, u.user_id, usp.value, us.previous_rank, pickee_id as internal_pickee_id, external_pickee_id
            p.pickee_name, p.price, active,
            dense_rank() OVER (order by value desc, $extraOrder u.user_id) as ranking
             from useru u
             join card c using(user_id)
             join team t on (t.card_id = c.card_id and $teamPeriodFilter)
             join pickee p using(pickee_id)
             join user_stat us on (us.user_id = u.user_id and us.stat_field_id = {statFieldId})
             join user_stat_period usp on (usp.user_stat_id = us.user_stat_id and $statPeriodFilter)
             $extraJoins
             """
      }
    }
    println(q)
    SQL(q).on("period" -> period, "statFieldId" -> statFieldId).as(rankingParser.*)
  }

  override def updatePreviousRank(userId: Long, statFieldId: Long, previousRank: Int)(implicit c: Connection): Unit = {
    SQL(
      "update user_stat set previous_rank = {previousRank} where user_id = {userId} and stat_field_id = {statFieldId};"
    ).on("previousRank" -> previousRank, "userId" -> userId, "statFieldId" -> statFieldId).executeUpdate()
  }

  override def joinUser(externalUserId: Long, username: String, league: LeagueRow): UserRow = {
    db.withConnection { implicit c: Connection =>
      println("in joinuser")
      val newUser = insertUser(league, externalUserId, username)
      val newUserStatIds = leagueRepo.getStatFields(league.leagueId).map(
        sf => insertUserStat(sf.statFieldId, newUser.userId)
      )
      newUserStatIds.foreach(sid => insertUserStatDaily(sid, None))

      leagueRepo.getPeriods(league.leagueId).foreach(p =>
        newUserStatIds.foreach(sid => insertUserStatDaily(sid, Some(p.value)))
      )

      newUser
    }
  }

  override def userInLeague(externalUserId: Long, leagueId: Long)(implicit c: Connection): Boolean = {
    SQL(s"select 1 from useru where league_id = $leagueId and external_user_id = $externalUserId").
      as(SqlParser.scalar[Int].singleOpt).isDefined
  }

  override def updateHistoricRanks(leagueId: Long)(implicit c: Connection): Unit = {
    leagueRepo.getStatFields(leagueId).foreach(sf => {
      val userStatsOverall = getUserStats(Some(leagueId), None, Some(sf.statFieldId), None, true)
      var lastScore = Double.MaxValue
      var lastScoreRank = 0
      userStatsOverall.zipWithIndex.map({
        case (row, i) => {
          val value = row.value
          val rank = if (value == lastScore) lastScoreRank else i + 1
          lastScore = value
          lastScoreRank = rank
          val previousRank = rank
          (row.userId, sf.statFieldId, previousRank)
        }
      }).foreach(x => (updatePreviousRank _).tupled(x))
      // can do all update in one call if append then update outside loop
      // TODO reimplement this
      //      val pickeeStatsOverall = pickeeRepo.getPickeeStat(league.leagueId, sf.statFieldId, None).map(_._1)
      //      val newPickeeStat = pickeeStatsOverall.zipWithIndex.map(
      //        { case (p, i) => p.previousRank = i + 1; p }
      //      )
      //      // can do all update in one call if append then update outside loop
      //      pickeeStatTable.update(newPickeeStat)
    })
  }

  override def setlateEntryLockTs(userId: Long)(implicit c: Connection): Int = {
    SQL"""update useru set late_entry_lock_ts = now() where user_id = $userId""".executeUpdate()
  }
}

