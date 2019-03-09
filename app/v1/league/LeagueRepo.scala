package v1.league

import java.sql.Timestamp
import javax.inject.{Inject, Singleton}
import entry.SquerylEntrypointForMyApp._
import akka.actor.ActorSystem
import play.api.mvc.Result
import play.api.mvc.Results.{BadRequest, InternalServerError}
import play.api.libs.concurrent.CustomExecutionContext
import play.api.libs.json._

import models.AppDB._
import models._
import v1.leagueuser.LeagueUserRepo
import v1.pickee.PickeeRepo

class LeagueExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

case class LeagueFull(league: League, limits: Iterable[LimitTypeOut], periods: Iterable[Period], currentPeriod: Option[Period], statFields: Iterable[LeagueStatField])

object LeagueFull{
  implicit val implicitWrites = new Writes[LeagueFull] {
    def writes(league: LeagueFull): JsValue = {
      Json.obj(
        "id" -> league.league.id,
        "name" -> league.league.name,
        "gameId" -> league.league.gameId,
        "tournamentId" -> league.league.tournamentId,
        "isPrivate" -> league.league.isPrivate,
        "tournamentId" -> league.league.tournamentId,
        "pickee" -> league.league.pickeeDescription,
        "teamSize" -> league.league.teamSize,
        "transferLimit" -> league.league.transferLimit, // use -1 for no transfer limit I think. only applies after period 1 start
        "transferWildcard" -> league.league.transferWildcard,
        "transferOpen" -> league.league.transferOpen,
        "transferDelayMinutes" -> league.league.transferDelayMinutes,
        "transferBlockedDuringPeriod" -> league.league.transferBlockedDuringPeriod,
        "startingMoney" -> league.league.startingMoney,
        "statFields" -> league.statFields.map(_.name),
        "limitTypes" -> league.limits,
        "periods" -> league.periods,
        "currentPeriod" -> league.currentPeriod,
        "started" -> league.league.started,
        "ended" -> (league.currentPeriod.exists(_.ended) && league.currentPeriod.exists(_.nextPeriodId.isEmpty)),
        "pickeeDescription" -> league.league.pickeeDescription,
        "periodDescription" -> league.league.periodDescription,
        "noWildcardForLateRegister" -> league.league.noWildcardForLateRegister,
        "applyPointsAtStartTime" -> league.league.applyPointsAtStartTime,
        "url" -> {if (league.league.urlVerified) league.league.url else ""}
      )
    }
  }
}

case class LeagueFullQuery(league: League, period: Option[Period], limitType: Option[LimitType], limit: Option[Limit], statField: Option[LeagueStatField])


trait LeagueRepo{
  def get(id: Long): Option[League]
  def getWithRelated(id: Long): LeagueFull
  def insert(formInput: LeagueFormInput): League
  def update(league: League, input: UpdateLeagueFormInput): League
  def getStatFieldNames(statFields: Iterable[LeagueStatField]): Array[String]
  def insertLeagueStatField(leagueId: Long, name: String): LeagueStatField
  def insertLeaguePrize(leagueId: Long, description: String, email: String): LeaguePrize
  def insertPeriod(leagueId: Long, input: PeriodInput, period: Int, nextPeriodId: Option[Long]): Period
  def getNextPeriod(league: League): Either[Result, Period]
  def leagueFullQueryExtractor(q: Iterable[LeagueFullQuery]): LeagueFull
  def updatePeriod(leagueId: Long, periodValue: Int, start: Option[Timestamp], end: Option[Timestamp], multiplier: Option[Double]): Period
  def updateHistoricRanks(league: League)
  def postStartPeriodHook(league: League, period: Period, timestamp: Timestamp)
  def postEndPeriodHook(league: League, period: Period, timestamp: Timestamp)
  def startPeriods(currentTime: Timestamp)
  def endPeriods(currentTime: Timestamp)
}

@Singleton
class LeagueRepoImpl @Inject()(leagueUserRepo: LeagueUserRepo, pickeeRepo: PickeeRepo)(implicit ec: LeagueExecutionContext) extends LeagueRepo{
  override def get(id: Long): Option[League] = {
    leagueTable.lookup(id)
  }

  override def getWithRelated(id: Long): LeagueFull = {
    val queryResult = join(leagueTable, periodTable.leftOuter, limitTypeTable.leftOuter, limitTable.leftOuter, leagueStatFieldTable.leftOuter)((l, p, ft, f, s) =>
        where(l.id === id)
        select((l, p, ft, f, s))
        on(l.id === p.map(_.leagueId), l.id === ft.map(_.leagueId), f.map(_.limitTypeId) === ft.map(_.id), s.map(_.leagueId) === l.id)
        ).map(LeagueFullQuery.tupled(_))
    leagueFullQueryExtractor(queryResult)
        // deconstruct tuple
        // check what db queries would actuallly return
  }

  override def getStatFieldNames(statFields: Iterable[LeagueStatField]): Array[String] = {
    statFields.map(_.name).toArray
  }

  override def insert(input: LeagueFormInput): League = {
    leagueTable.insert(new League(input.name, input.apiKey, input.gameId, input.isPrivate, input.tournamentId, input.pickeeDescription,
      input.periodDescription, input.transferInfo.transferLimit, input.transferInfo.transferWildcard,
      input.startingMoney, input.teamSize, transferBlockedDuringPeriod=input.transferInfo.transferBlockedDuringPeriod,
      transferDelayMinutes=input.transferInfo.transferDelayMinutes, url=input.url.getOrElse(""), applyPointsAtStartTime=input.applyPointsAtStartTime,
      noWildcardForLateRegister=input.transferInfo.noWildcardForLateRegister
    ))
  }

  override def update(league: League, input: UpdateLeagueFormInput): League = {
    league.name = input.name.getOrElse(league.name)
    league.isPrivate = input.isPrivate.getOrElse(league.isPrivate)
    league.transferOpen = input.transferOpen.getOrElse(league.transferOpen)
    league.transferBlockedDuringPeriod = input.transferBlockedDuringPeriod.getOrElse(league.transferBlockedDuringPeriod)
    league.transferDelayMinutes = input.transferDelayMinutes.getOrElse(league.transferDelayMinutes)
    println(league.transferDelayMinutes)
    league.periodDescription = input.periodDescription.getOrElse(league.periodDescription)
    league.pickeeDescription = input.pickeeDescription.getOrElse(league.pickeeDescription)
    league.transferLimit = if (input.transferLimit.nonEmpty) input.transferLimit else league.transferLimit
    league.transferWildcard = input.transferWildcard.getOrElse(league.transferWildcard)
    league.noWildcardForLateRegister = input.noWildcardForLateRegister.getOrElse(league.noWildcardForLateRegister)
    league.applyPointsAtStartTime = input.applyPointsAtStartTime.getOrElse(league.applyPointsAtStartTime)
    input.url.foreach(u => {
      league.url = u
      league.urlVerified = false
    })
    leagueTable.update(league)
    league
  }

  override def insertLeaguePrize(leagueId: Long, description: String, email: String): LeaguePrize = {
    leaguePrizeTable.insert(new LeaguePrize(leagueId, description, email))
  }

  override def insertLeagueStatField(leagueId: Long, name: String): LeagueStatField = {
    leagueStatFieldTable.insert(new LeagueStatField(leagueId, name))
  }

  override def insertPeriod(leagueId: Long, input: PeriodInput, period: Int, nextPeriodId: Option[Long]): Period = {
    periodTable.insert(new Period(leagueId, period, input.start, input.end, input.multiplier, nextPeriodId))
  }

  override def getNextPeriod(league: League): Either[Result, Period] = {
    // check if is above max?
    league.currentPeriod match {
      case Some(p) if !p.ended => Left(BadRequest("Must end current period before start next"))
      case Some(p) => {
        p.nextPeriodId match {
          case Some(np) => periodTable.lookup(np).toRight(InternalServerError(s"Could not find next period $np, for period ${p.id}"))
          case None => Left(BadRequest("No more periods left to start. League is over"))
        }
      }
      case None => {
        Right(league.firstPeriod)
      }
    }
  }

  override def leagueFullQueryExtractor(q: Iterable[LeagueFullQuery]): LeagueFull = {
    val league = q.toList.head.league
    val periods = q.flatMap(_.period).toSet
    println(periods)
    val currentPeriod = periods.find(p => league.currentPeriodId.contains(p.id))
    val statFields = q.flatMap(_.statField).toSet
    val limits = q.map(f => (f.limitType, f.limit)).filter(_._2.isDefined).map(f => (f._1.get, f._2.get)).groupBy(_._1).mapValues(_.map(_._2).toSet)
    // keep limits as well
    val limitsOut = limits.map({case (k, v) => LimitTypeOut(k.name, k.description, v)})
    LeagueFull(league, limitsOut, periods, currentPeriod, statFields)
  }

  override def updatePeriod(leagueId: Long, periodValue: Int, start: Option[Timestamp], end: Option[Timestamp], multiplier: Option[Double]): Period = {
    val period = from(periodTable)(p => 
        where(p.leagueId === leagueId and p.value === periodValue)
        select(p)
      ).single
    period.start = start.getOrElse(period.start)
    period.end = end.getOrElse(period.end)
    period.multiplier = multiplier.getOrElse(period.multiplier)
    periodTable.update(period)
    period
  }

  override def updateHistoricRanks(league: League) = {
    // TODO this needs to group by the stat field.
    // currently will do weird ranks
    league.statFields.foreach(sf => {
      val leagueUserStatsOverall =
        leagueUserRepo.getLeagueUserStats(league.id, sf.id, None)
      var lastScore = Double.MaxValue // TODO java max num
      var lastScoreRank = 0
      val newLeagueUserStat = leagueUserStatsOverall.zipWithIndex.map({
        case ((lus, s), i) => {
          val value = s.value
          val rank = if (value == lastScore) lastScoreRank else i + 1
          lastScore = value
          lastScoreRank = rank
          lus.previousRank = rank
          lus
        }
      })
      // can do all update in one call if append then update outside loop
      leagueUserRepo.updateLeagueUserStat(newLeagueUserStat)
      val pickeeStatsOverall = pickeeRepo.getPickeeStat(league.id, sf.id, None).map(_._1)
      val newPickeeStat = pickeeStatsOverall.zipWithIndex.map(
        { case (p, i) => p.previousRank = i + 1; p }
      )
      // can do all update in one call if append then update outside loop
      pickeeStatTable.update(newPickeeStat)
    })
  }

  override def postEndPeriodHook(league: League, period: Period, timestamp: Timestamp) = {
    period.ended = true
    period.end = timestamp
    periodTable.update(period)
    league.transferOpen = true
    leagueTable.update(league)
  }

  override def postStartPeriodHook(league: League, period: Period, timestamp: Timestamp) = {
    period.start = timestamp
    periodTable.update(period)
    league.currentPeriodId = Some(period.id)
    if (league.transferBlockedDuringPeriod) {
      league.transferOpen = false
    }
    leagueTable.update(league)
    updateHistoricRanks(league)
  }

  override def endPeriods(currentTime: Timestamp) = {
    from(leagueTable, periodTable)((l,p) =>
          where(l.currentPeriodId === p.id and p.ended === false and p.end <= currentTime and p.nextPeriodId.isNotNull)
          select((l, p))
          ).foreach(t => postEndPeriodHook(t._1, t._2, currentTime))
  }
  override def startPeriods(currentTime: Timestamp) = {
    from(leagueTable, periodTable)((l,p) =>
      // looking for period that a) isnt current period, b) isnt old ended period (so must be future period!)
      // and is future period that should have started...so lets start it
      where(p.leagueId === l.id and (l.currentPeriodId.isNull or not(l.currentPeriodId === p.id)) and p.ended === false and p.start <= currentTime)
        select((l, p))).foreach(t => postStartPeriodHook(t._1, t._2, currentTime))
  }
}

